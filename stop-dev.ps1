#Requires -Version 5.1
<#
.SYNOPSIS
    Derruba o ambiente de desenvolvimento que o start-dev.ps1 subiu.

.DESCRIPTION
    Para o backend Java e o WireMock pelos PIDs gravados em .dev-run, e em
    seguida para os containers de Postgres e Redis.

    Usa `docker compose stop`, nao `down`: `stop` preserva os containers e os
    volumes. Os dados do banco sobrevivem de qualquer forma, porque moram em
    volumes nomeados, mas `stop` evita recriar container a cada ciclo.

    So mata processo que ele reconhece. Um PID gravado que hoje pertenca a
    outro programa -- o sistema reaproveita numero de PID -- e ignorado com
    aviso, nunca morto as cegas.

.PARAMETER KeepDatabase
    Deixa Postgres e Redis de pe; para apenas backend e WireMock. Util para
    reiniciar so a aplicacao sem esperar o banco de novo.

.PARAMETER RemoveContainers
    Alem de parar, remove os containers (`down`). Os volumes nomeados e os
    dados NAO sao apagados.

.EXAMPLE
    .\stop-dev.ps1

.EXAMPLE
    .\stop-dev.ps1 -KeepDatabase
#>
[CmdletBinding()]
param(
    [switch]$KeepDatabase,
    [switch]$RemoveContainers
)

$ErrorActionPreference = 'Stop'

$Raiz       = $PSScriptRoot
$DirRun     = Join-Path $Raiz '.dev-run'
$ArquivoEnv = Join-Path $Raiz '.env.dev'

Write-Host ''
Write-Host 'Derrubando o ambiente de desenvolvimento' -ForegroundColor White
Write-Host ('-' * 62) -ForegroundColor DarkGray

# ===========================================================================
# 1. Os processos locais
# ===========================================================================

<#
    Para um processo pelo PID gravado, com duas conferencias antes: que o
    processo exista, e que seja mesmo java. O sistema reaproveita numeros de
    PID, e matar as cegas o numero que estava num arquivo de ontem e como
    matar um estranho porque ele mora no endereco antigo de um conhecido.
#>
function Stop-PeloArquivoDePid([string]$Nome, [string]$Arquivo) {
    if (-not (Test-Path -LiteralPath $Arquivo)) {
        Write-Host "    $Nome : nenhum PID registrado" -ForegroundColor DarkGray
        return
    }

    $conteudo = (Get-Content -LiteralPath $Arquivo -Raw).Trim()
    $processId = 0
    if (-not [int]::TryParse($conteudo, [ref]$processId)) {
        Write-Host "    $Nome : PID ilegivel em $Arquivo" -ForegroundColor Yellow
        Remove-Item -LiteralPath $Arquivo -Force
        return
    }

    $proc = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host "    $Nome : PID $processId ja nao existe" -ForegroundColor DarkGray
        Remove-Item -LiteralPath $Arquivo -Force
        return
    }

    if ($proc.ProcessName -ne 'java') {
        Write-Host "    $Nome : PID $processId agora e '$($proc.ProcessName)', nao java -- NAO vou mata-lo" -ForegroundColor Yellow
        Write-Host '             (o sistema reaproveitou o numero do PID)' -ForegroundColor DarkGray
        Remove-Item -LiteralPath $Arquivo -Force
        return
    }

    Stop-Process -Id $processId -Force
    Write-Host "    $Nome : PID $processId encerrado" -ForegroundColor Green
    Remove-Item -LiteralPath $Arquivo -Force
}

Write-Host ''
Write-Host '==> Processos locais' -ForegroundColor Cyan
Stop-PeloArquivoDePid 'backend ' (Join-Path $DirRun 'backend.pid')
Stop-PeloArquivoDePid 'wiremock' (Join-Path $DirRun 'wiremock.pid')

# ===========================================================================
# 2. Os containers
# ===========================================================================

if ($KeepDatabase) {
    Write-Host ''
    Write-Host '==> Containers: mantidos de pe (-KeepDatabase)' -ForegroundColor Cyan
}
else {
    Write-Host ''
    Write-Host '==> Containers' -ForegroundColor Cyan

    # cmd /c, e nao 2>$null: no PowerShell 5.1 o stderr de executavel nativo
    # redirecionado dentro do PowerShell vira NativeCommandError e, com
    # ErrorActionPreference = 'Stop', aborta o script -- justamente quando o
    # engine esta fora, que e o caso que este trecho trata.
    cmd /c "docker info >nul 2>nul"
    if ($LASTEXITCODE -ne 0) {
        Write-Host '    o engine do Docker nao responde; nada a parar' -ForegroundColor DarkGray
    }
    else {
        cmd /c "docker compose version >nul 2>nul"
        if ($LASTEXITCODE -eq 0) {
            $Compose = 'docker'
            $ComposeArgs0 = @('compose')
        }
        else {
            $Compose = 'docker-compose'
            $ComposeArgs0 = @()
        }

        $base = $ComposeArgs0 + @('-f', (Join-Path $Raiz 'docker-compose.yml'))
        if (Test-Path -LiteralPath $ArquivoEnv) {
            $base = $ComposeArgs0 + @('--env-file', $ArquivoEnv,
                                      '-f', (Join-Path $Raiz 'docker-compose.yml'))
        }

        if ($RemoveContainers) {
            # Sem -v: os volumes nomeados (e os dados) ficam.
            & $Compose @($base + @('down'))
            Write-Host '    containers removidos; volumes e dados preservados' -ForegroundColor Green
        }
        else {
            & $Compose @($base + @('stop', 'postgres', 'redis'))
            Write-Host '    postgres e redis parados' -ForegroundColor Green
        }
    }
}

# ===========================================================================
# 3. O que sobrou
# ===========================================================================

Write-Host ''
Write-Host '==> Conferencia' -ForegroundColor Cyan

$portas = @(8081, 8089)
if (Test-Path -LiteralPath $ArquivoEnv) {
    $portas = @()
    foreach ($linha in (Get-Content -LiteralPath $ArquivoEnv -Encoding UTF8)) {
        if ($linha -match '^(APP_PORT|WIREMOCK_PORT)\s*=\s*(\d+)') {
            $portas += [int]$Matches[2]
        }
    }
    if (-not $KeepDatabase) {
        foreach ($linha in (Get-Content -LiteralPath $ArquivoEnv -Encoding UTF8)) {
            if ($linha -match '^(POSTGRES_PORT|REDIS_PORT)\s*=\s*(\d+)') {
                $portas += [int]$Matches[2]
            }
        }
    }
}

$aindaAbertas = @()
foreach ($porta in $portas) {
    $c = Get-NetTCPConnection -LocalPort $porta -State Listen -ErrorAction SilentlyContinue
    if ($c) {
        $dono = (Get-Process -Id $c[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
        $aindaAbertas += "$porta ($dono, PID $($c[0].OwningProcess))"
    }
}

if ($aindaAbertas.Count -eq 0) {
    Write-Host '    todas as portas do ambiente estao livres' -ForegroundColor Green
}
else {
    Write-Host '    AINDA ESCUTANDO:' -ForegroundColor Yellow
    foreach ($p in $aindaAbertas) { Write-Host "      $p" -ForegroundColor Yellow }
    Write-Host '    (processo iniciado fora do start-dev.ps1 nao e tocado por este script)' -ForegroundColor DarkGray
}

Write-Host ''
Write-Host "Logs preservados em $DirRun" -ForegroundColor DarkGray
Write-Host 'Para subir de novo: .\start-dev.ps1' -ForegroundColor Gray
Write-Host ''
