#Requires -Version 5.1
<#
.SYNOPSIS
    Sobe o ambiente de desenvolvimento local inteiro: Postgres, Redis,
    WireMock no lugar da Evolution, e o backend Java.

.DESCRIPTION
    Idempotente: cada peca e verificada antes de ser iniciada, e uma que ja
    esteja saudavel e reaproveitada em vez de duplicada. Se qualquer peca nao
    subir, o script PARA e diz qual e por que -- nunca segue em silencio.

    Os segredos vem de .env.dev, na raiz do projeto, que nao entra em commit.

    A Evolution real NUNCA e usada: a instancia de producao esta pareada com
    o WhatsApp do barbeiro, e EVOLUTION_API_URL aponta para o WireMock local.
    O WireMock sobe SEM --disable-http2-plain de proposito: com a flag ele
    aceita um envelope HTTP que um servidor estrito recusaria, e foi assim que
    uma divergencia de Transfer-Encoding passou despercebida uma vez.

.PARAMETER Rebuild
    Reconstroi o jar mesmo que ele exista.

.PARAMETER TimeoutSeconds
    Teto de espera para cada peca ficar saudavel. Padrao 120.

.EXAMPLE
    .\start-dev.ps1

.EXAMPLE
    .\start-dev.ps1 -Rebuild
#>
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

$Raiz       = $PSScriptRoot
$ArquivoEnv = Join-Path $Raiz '.env.dev'
$DirRun     = Join-Path $Raiz '.dev-run'
$Jar        = Join-Path $Raiz 'target\barbearia-api-0.0.1-SNAPSHOT.jar'

# ===========================================================================
# Utilitarios
# ===========================================================================

$script:Resumo = @()

function Add-Resumo([string]$Servico, [string]$Porta, [string]$Estado, [string]$Nota) {
    $script:Resumo += [pscustomobject]@{
        Servico = $Servico; Porta = $Porta; Estado = $Estado; Nota = $Nota
    }
}

function Write-Passo([string]$Texto) {
    Write-Host ''
    Write-Host "==> $Texto" -ForegroundColor Cyan
}

function Write-Ok([string]$Texto) {
    Write-Host "    [ok] $Texto" -ForegroundColor Green
}

function Write-Nota([string]$Texto) {
    Write-Host "    $Texto" -ForegroundColor DarkGray
}

<#
    Falha ruidosa. A polaridade e a mesma do EnvironmentValidator da
    aplicacao: nomear a causa provavel, nao descrever o sintoma.
#>
function Stop-ComErro([string]$Oque, [string]$Porque, [string[]]$ComoCorrigir) {
    Write-Host ''
    Write-Host "FALHOU: $Oque" -ForegroundColor Red
    Write-Host ''
    Write-Host "  MOTIVO: $Porque" -ForegroundColor Red
    if ($ComoCorrigir) {
        Write-Host ''
        Write-Host '  COMO CORRIGIR:' -ForegroundColor Yellow
        foreach ($linha in $ComoCorrigir) { Write-Host "    - $linha" -ForegroundColor Yellow }
    }
    Write-Host ''
    Write-Host 'Nada mais foi iniciado. O que ja estava de pe continua de pe.' -ForegroundColor DarkGray
    exit 1
}

function Read-DotEnv([string]$Caminho) {
    $mapa = @{}
    foreach ($linha in (Get-Content -LiteralPath $Caminho -Encoding UTF8)) {
        $t = $linha.Trim()
        if ($t.Length -eq 0) { continue }
        if ($t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $chave = $t.Substring(0, $i).Trim()
        $valor = $t.Substring($i + 1).Trim().Trim('"')
        $mapa[$chave] = $valor
    }
    return $mapa
}

<#
    Status HTTP de uma URL, ou 0 quando nao ha resposta. Nao lanca: o chamador
    decide o que um 0 significa.
#>
function Get-StatusHttp([string]$Url, [int]$TimeoutSec = 3) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return [int]$r.StatusCode
    }
    catch [System.Net.WebException] {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
    catch {
        return 0
    }
}

<#
    Repete a condicao ate ela devolver $true ou o tempo acabar. A condicao
    pode devolver uma string em vez de $false: ela vira o "ultimo estado"
    impresso no estouro, para o erro dizer onde parou.
#>
function Wait-Condicao([scriptblock]$Condicao, [int]$Segundos, [string]$Oque) {
    $fim = (Get-Date).AddSeconds($Segundos)
    $ultimo = '(nenhum)'
    while ((Get-Date) -lt $fim) {
        $r = & $Condicao
        if ($r -eq $true) { return $true }
        if ($r -is [string]) { $ultimo = $r }
        Start-Sleep -Milliseconds 1000
    }
    Write-Nota "tempo esgotado esperando $Oque ($Segundos s). Ultimo estado: $ultimo"
    return $false
}

<#
    Roda um comando `docker` descartando o stderr, e devolve o stdout.

    <b>Por que via `cmd /c` e nao com `2>$null`.</b> No PowerShell 5.1, o
    stderr de um executavel nativo redirecionado dentro do PowerShell vira um
    ErrorRecord de NativeCommandError -- e com `$ErrorActionPreference =
    'Stop'` isso ABORTA o script. Ou seja, `2>$null` fazia este script morrer
    exatamente quando o engine do Docker estava fora, que e o caso que ele
    existe para tratar. O `cmd /c` descarta a saida de erro antes de ela
    chegar ao PowerShell, e o `$LASTEXITCODE` continua sendo o do docker.
#>
function Invoke-Docker([string]$Argumentos) {
    return (cmd /c "docker $Argumentos 2>nul")
}

function Test-DockerNoAr() {
    Invoke-Docker 'info' | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Test-PortaEmUso([int]$Porta) {
    $c = Get-NetTCPConnection -LocalPort $Porta -State Listen -ErrorAction SilentlyContinue
    if ($c) { return $true }
    return $false
}

function Get-PidDaPorta([int]$Porta) {
    $c = Get-NetTCPConnection -LocalPort $Porta -State Listen -ErrorAction SilentlyContinue |
         Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
    return 0
}

function Get-UltimasLinhas([string]$Caminho, [int]$Quantas = 15) {
    if (-not (Test-Path -LiteralPath $Caminho)) { return @() }
    return Get-Content -LiteralPath $Caminho -Tail $Quantas -ErrorAction SilentlyContinue
}

<#
    As linhas do log que explicam uma falha de boot, na ordem de utilidade.
    O EnvironmentValidator e o VerificaAConstraintDeSobreposicao escrevem
    mensagens que nomeiam a causa provavel; sao essas que interessam, nao o
    stack inteiro.
#>
function Get-LinhasDeDiagnostico([string]$Caminho) {
    if (-not (Test-Path -LiteralPath $Caminho)) { return @() }
    $padrao = 'APPLICATION FAILED TO START|IllegalStateException|' +
              'obrigatoria, nao definida|deve ter no minimo|' +
              'caracteres hexadecimais|nao existe em|' +
              'Connection refused|Could not find a valid Docker|' +
              'was already in use|Caused by'
    $achadas = Select-String -LiteralPath $Caminho -Pattern $padrao -ErrorAction SilentlyContinue |
               Select-Object -ExpandProperty Line -First 12
    if ($achadas) { return $achadas }
    return (Get-UltimasLinhas $Caminho 15)
}

# ===========================================================================
# 0. Pre-requisitos
# ===========================================================================

Write-Host ''
Write-Host 'Ambiente de desenvolvimento -- barbearia-api' -ForegroundColor White
Write-Host ('-' * 62) -ForegroundColor DarkGray

if (-not (Test-Path -LiteralPath $ArquivoEnv)) {
    Stop-ComErro '.env.dev nao encontrado' `
        "o script le os segredos de $ArquivoEnv, e o arquivo nao existe" `
        @('Copie o modelo e preencha: .env.dev.example -> .env.dev',
          'A ENCRYPTION_KEY precisa ser a MESMA com que o banco local foi semeado,',
          'senao nome e telefone dos agendamentos existentes nao decifram')
}

$cfg = Read-DotEnv $ArquivoEnv

# Validacao com as mesmas regras do EnvironmentValidator, so que ANTES de
# subir a JVM: errar aqui custa um segundo, errar la custa trinta.
$faltando = @()
foreach ($k in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_PORT',
                 'REDIS_PORT', 'DB_USERNAME', 'DB_PASSWORD', 'ENCRYPTION_KEY',
                 'JWT_SECRET', 'JWT_REFRESH_SECRET', 'CORS_ORIGIN', 'APP_PORT',
                 'WIREMOCK_PORT')) {
    if (-not $cfg.ContainsKey($k) -or [string]::IsNullOrWhiteSpace($cfg[$k])) {
        $faltando += $k
    }
}
if ($faltando.Count -gt 0) {
    Stop-ComErro 'faltam variaveis em .env.dev' `
        ('ausentes ou vazias: ' + ($faltando -join ', ')) `
        @('Compare com .env.dev.example')
}

if ($cfg['ENCRYPTION_KEY'] -notmatch '^[0-9a-fA-F]{64}$') {
    Stop-ComErro 'ENCRYPTION_KEY malformada' `
        'deve ter exatamente 64 caracteres hexadecimais (chave AES-256 de 32 bytes)' `
        @('A aplicacao recusaria subir com esta mesma mensagem, no EnvironmentValidator')
}
foreach ($k in @('JWT_SECRET', 'JWT_REFRESH_SECRET')) {
    if ($cfg[$k].Length -lt 32) {
        Stop-ComErro "$k curta demais" `
            "tem $($cfg[$k].Length) caracteres; o minimo e 32" `
            @('A aplicacao recusaria subir com esta mesma mensagem')
    }
}
Write-Ok '.env.dev lido e validado'

foreach ($exe in @('docker', 'java')) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) {
        Stop-ComErro "$exe nao esta no PATH" "o script precisa de $exe" `
            @("Instale $exe ou ajuste o PATH")
    }
}

New-Item -ItemType Directory -Force -Path $DirRun | Out-Null

$PortaPg  = [int]$cfg['POSTGRES_PORT']
$PortaRd  = [int]$cfg['REDIS_PORT']
$PortaApp = [int]$cfg['APP_PORT']
$PortaWm  = [int]$cfg['WIREMOCK_PORT']
if ($cfg.ContainsKey('WIREMOCK_VERSION')) { $VersaoWm = $cfg['WIREMOCK_VERSION'] }
else { $VersaoWm = '3.9.1' }

# ===========================================================================
# 1. Postgres e Redis
# ===========================================================================

Write-Passo '1/4  Postgres e Redis'

# O engine do Docker Desktop cai sozinho com alguma frequencia nesta maquina,
# e o sintoma a jusante e ilegivel: o Testcontainers reclama de "valid Docker
# environment", a aplicacao perde o pool, o navegador mostra erro de rede sem
# explicacao. Melhor detectar aqui e nomear.
if (-not (Test-DockerNoAr)) {
    Write-Nota 'o engine do Docker nao responde; tentando iniciar o Docker Desktop'
    $exeDocker = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path -LiteralPath $exeDocker) {
        Start-Process -FilePath $exeDocker | Out-Null
        $subiu = Wait-Condicao {
            if (Test-DockerNoAr) { return $true }
            return 'engine ainda nao responde'
        } $TimeoutSeconds 'o engine do Docker'
        if (-not $subiu) {
            Stop-ComErro 'o engine do Docker nao subiu' `
                "esperei $TimeoutSeconds s e 'docker info' continuou falhando" `
                @('Abra o Docker Desktop na mao e espere o icone ficar verde',
                  'Depois rode este script de novo')
        }
    }
    else {
        Stop-ComErro 'o engine do Docker nao responde' `
            "'docker info' falhou e nao achei o Docker Desktop em $exeDocker" `
            @('Inicie o Docker e rode de novo')
    }
}
Write-Ok 'engine do Docker respondendo'

# `docker compose` (plugin) ou `docker-compose` (binario antigo).
Invoke-Docker 'compose version' | Out-Null
if ($LASTEXITCODE -eq 0) {
    $Compose = 'docker'
    $ComposeArgs0 = @('compose')
}
else {
    if (Get-Command 'docker-compose' -ErrorAction SilentlyContinue) {
        $Compose = 'docker-compose'
        $ComposeArgs0 = @()
    }
    else {
        Stop-ComErro 'docker compose indisponivel' `
            "nem 'docker compose' nem 'docker-compose' funcionam" `
            @('Atualize o Docker Desktop')
    }
}

# Sobe SO postgres e redis. O servico `app` do docker-compose.yml existe para
# rodar a aplicacao dentro de container; aqui ela roda como jar local, para
# poder ser reiniciada sem rebuild de imagem.
$argsUp = $ComposeArgs0 + @('--env-file', $ArquivoEnv,
                            '-f', (Join-Path $Raiz 'docker-compose.yml'),
                            'up', '-d', 'postgres', 'redis')
# O compose escreve o progresso no stderr. Com 'Stop', a primeira linha dele
# abortaria o script antes de a verificacao de $LASTEXITCODE abaixo poder dar
# uma mensagem util.
$anterior = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $Compose @argsUp
$codigoDoCompose = $LASTEXITCODE
$ErrorActionPreference = $anterior
if ($codigoDoCompose -ne 0) {
    Stop-ComErro 'docker compose up falhou' `
        "o comando saiu com codigo $codigoDoCompose (a saida dele esta acima)" `
        @("Porta ocupada? Veja quem escuta em $PortaPg e $PortaRd",
          'Senha trocada? O volume barbearia-postgres-data foi criado com a senha',
          'original, e o Postgres recusa autenticacao se POSTGRES_PASSWORD mudar')
}

foreach ($nome in @('barbearia-postgres', 'barbearia-redis')) {
    if ($nome -eq 'barbearia-postgres') { $porta = $PortaPg } else { $porta = $PortaRd }

    $saudavel = Wait-Condicao {
        $bruto = Invoke-Docker "inspect $nome"
        if ($LASTEXITCODE -ne 0) { return 'container ainda nao existe' }
        $estado = ($bruto | ConvertFrom-Json)[0].State
        if ($estado.Health) { $st = $estado.Health.Status } else { $st = 'sem-healthcheck' }
        if ($st -eq 'healthy') { return $true }
        if ($st -eq 'sem-healthcheck') { return $true }
        return "healthcheck em '$st'"
    } $TimeoutSeconds "$nome ficar healthy"

    if (-not $saudavel) {
        $saidaHc = (Invoke-Docker "inspect $nome" | ConvertFrom-Json)[0].State.Health.Log |
                   Select-Object -Last 1 -ExpandProperty Output
        Stop-ComErro "$nome nao ficou healthy" `
            "o healthcheck do container nao passou em $TimeoutSeconds s" `
            @("Saida do healthcheck: $saidaHc",
              "Log completo: docker logs $nome --tail 50")
    }
    Write-Ok "$nome healthy na porta $porta"
    Add-Resumo $nome "$porta" 'healthy' 'container'
}

# ===========================================================================
# 2. WireMock, no lugar da Evolution
# ===========================================================================

Write-Passo '2/4  WireMock (Evolution falsa)'

$urlWmAdmin = "http://localhost:$PortaWm/__admin/health"

if ((Get-StatusHttp $urlWmAdmin) -eq 200) {
    Write-Ok "ja estava no ar na porta $PortaWm -- reaproveitado"
    Add-Resumo 'wiremock' "$PortaWm" 'no ar' 'reaproveitado'
}
elseif (Test-PortaEmUso $PortaWm) {
    $pidIntruso = Get-PidDaPorta $PortaWm
    $nomeIntruso = (Get-Process -Id $pidIntruso -ErrorAction SilentlyContinue).ProcessName
    Stop-ComErro "a porta $PortaWm esta ocupada por outro processo" `
        "o PID $pidIntruso ($nomeIntruso) escuta ali, mas nao responde como WireMock" `
        @('Derrube-o com .\stop-dev.ps1, ou mude WIREMOCK_PORT em .env.dev',
          'Se mudar, EVOLUTION_API_URL tem que apontar para a mesma porta')
}
else {
    $jarWm = Join-Path $env:USERPROFILE ".m2\repository\org\wiremock\wiremock-standalone\$VersaoWm\wiremock-standalone-$VersaoWm.jar"
    if (-not (Test-Path -LiteralPath $jarWm)) {
        Write-Nota "baixando wiremock-standalone $VersaoWm para o repositorio local do Maven"
        & (Join-Path $Raiz 'mvnw.cmd') -q dependency:get "-Dartifact=org.wiremock:wiremock-standalone:$VersaoWm"
        if (-not (Test-Path -LiteralPath $jarWm)) {
            Stop-ComErro 'nao consegui obter o jar do WireMock' `
                "esperava encontrar $jarWm depois do dependency:get" `
                @('Verifique a rede e o acesso ao Maven Central')
        }
    }

    # O stub aceita qualquer rota e devolve o 201 que a Evolution devolve. O
    # objetivo nunca e validar a resposta dela: e registrar o que o Java ENVIA.
    $dirMap = Join-Path $DirRun 'wiremock\mappings'
    New-Item -ItemType Directory -Force -Path $dirMap | Out-Null
    $stub = Join-Path $dirMap 'evolution-aceita-tudo.json'
    if (-not (Test-Path -LiteralPath $stub)) {
        $json = @'
{
  "priority": 10,
  "request": { "method": "ANY", "urlPattern": ".*" },
  "response": {
    "status": 201,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "key": {
        "remoteJid": "5522999990000@s.whatsapp.net",
        "fromMe": true,
        "id": "WIREMOCK0000000000000000"
      },
      "status": "PENDING",
      "message": { "extendedTextMessage": { "text": "ok" } },
      "messageTimestamp": "1762000000"
    }
  }
}
'@
        Set-Content -LiteralPath $stub -Value $json -Encoding utf8
    }

    $logWm = Join-Path $DirRun 'wiremock.log'
    $errWm = Join-Path $DirRun 'wiremock.err'
    # SEM --disable-http2-plain, de proposito: ver o cabecalho deste arquivo.
    $p = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $jarWm, '--port', "$PortaWm",
                        '--root-dir', (Join-Path $DirRun 'wiremock'), '--verbose') `
        -WorkingDirectory $DirRun -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logWm -RedirectStandardError $errWm
    Set-Content -LiteralPath (Join-Path $DirRun 'wiremock.pid') -Value $p.Id -Encoding ascii

    $subiu = Wait-Condicao {
        if ($p.HasExited) { return 'o processo java do WireMock morreu' }
        if ((Get-StatusHttp $urlWmAdmin) -eq 200) { return $true }
        return 'sem resposta no /__admin/health'
    } $TimeoutSeconds 'o WireMock'

    if (-not $subiu) {
        Stop-ComErro 'o WireMock nao subiu' `
            "nao respondeu em $urlWmAdmin dentro de $TimeoutSeconds s" `
            (@("Log: $logWm") + (Get-UltimasLinhas $errWm 8))
    }
    Write-Ok "no ar na porta $PortaWm (PID $($p.Id)), sem --disable-http2-plain"
    Add-Resumo 'wiremock' "$PortaWm" 'no ar' "PID $($p.Id)"
}

# ===========================================================================
# 3. Backend Java
# ===========================================================================

Write-Passo '3/4  Backend Java'

$urlSaude = "http://localhost:$PortaApp/actuator/health"

if ((Get-StatusHttp $urlSaude) -eq 200) {
    Write-Ok "ja estava no ar na porta $PortaApp -- reaproveitado"
    Write-Nota 'para trocar o jar: .\stop-dev.ps1 e depois .\start-dev.ps1 -Rebuild'
    Add-Resumo 'backend' "$PortaApp" 'UP' 'reaproveitado'
}
elseif (Test-PortaEmUso $PortaApp) {
    $pidIntruso = Get-PidDaPorta $PortaApp
    $nomeIntruso = (Get-Process -Id $pidIntruso -ErrorAction SilentlyContinue).ProcessName
    Stop-ComErro "a porta $PortaApp esta ocupada por outro processo" `
        "o PID $pidIntruso ($nomeIntruso) escuta ali, mas /actuator/health nao responde 200" `
        @('Pode ser uma instancia antiga que perdeu o banco quando o Docker caiu:',
          'nesse caso ela mantem a porta aberta e nao responde. Rode .\stop-dev.ps1',
          "Ou mude APP_PORT em .env.dev")
}
else {
    if ($Rebuild -or -not (Test-Path -LiteralPath $Jar)) {
        if ($Rebuild) { Write-Nota 'reconstruindo o jar (-Rebuild)' }
        else { Write-Nota 'jar ausente; construindo' }
        # Sem `clean`: nao ha motivo para apagar target inteiro, e o clean
        # falha quando alguma JVM ainda tem o jar aberto.
        & (Join-Path $Raiz 'mvnw.cmd') -q package -DskipTests
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $Jar)) {
            Stop-ComErro 'o build do jar falhou' `
                "mvnw package saiu com codigo $LASTEXITCODE" `
                @('Rode .\mvnw.cmd package -DskipTests para ver a saida completa')
        }
        Write-Ok 'jar construido'
    }

    # As URLs sao MONTADAS a partir das portas, nunca lidas de arquivo. E o
    # que impede o caso que ja aconteceu: .env dizendo 5432 enquanto o
    # container escuta em 5433, e a aplicacao falhando com "connection
    # refused" sem ninguem entender por que.
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:DATABASE_URL           = "jdbc:postgresql://localhost:$PortaPg/$($cfg['POSTGRES_DB'])"
    $env:REDIS_URL              = "redis://localhost:$PortaRd"
    $env:DB_USERNAME            = $cfg['DB_USERNAME']
    $env:DB_PASSWORD            = $cfg['DB_PASSWORD']
    $env:ENCRYPTION_KEY         = $cfg['ENCRYPTION_KEY']
    $env:JWT_SECRET             = $cfg['JWT_SECRET']
    $env:JWT_REFRESH_SECRET     = $cfg['JWT_REFRESH_SECRET']
    $env:CORS_ORIGIN            = $cfg['CORS_ORIGIN']
    $env:PORT                   = "$PortaApp"
    foreach ($opcional in @('EDGE_TOKEN', 'FRONTEND_URL', 'EVOLUTION_API_URL',
                            'EVOLUTION_API_KEY', 'EVOLUTION_INSTANCE_NAME',
                            'BARBER_PHONE')) {
        if ($cfg.ContainsKey($opcional)) {
            Set-Item -Path "env:$opcional" -Value $cfg[$opcional]
        }
    }

    $logApp = Join-Path $DirRun 'backend.log'
    $errApp = Join-Path $DirRun 'backend.err'
    $p = Start-Process -FilePath 'java' -ArgumentList @('-jar', $Jar) `
        -WorkingDirectory $Raiz -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logApp -RedirectStandardError $errApp
    Set-Content -LiteralPath (Join-Path $DirRun 'backend.pid') -Value $p.Id -Encoding ascii

    $subiu = Wait-Condicao {
        if ($p.HasExited) { return 'o processo java morreu' }
        if ((Get-StatusHttp $urlSaude) -eq 200) { return $true }
        return 'sem resposta no /actuator/health'
    } $TimeoutSeconds 'o backend'

    if (-not $subiu) {
        $diag = @("Log: $logApp") + (Get-LinhasDeDiagnostico $logApp) + (Get-UltimasLinhas $errApp 5)
        Stop-ComErro 'o backend nao subiu' `
            "nao respondeu 200 em $urlSaude dentro de $TimeoutSeconds s" $diag
    }
    Write-Ok "UP na porta $PortaApp (PID $($p.Id)), perfil dev"
    Add-Resumo 'backend' "$PortaApp" 'UP' "PID $($p.Id)"
}

# ===========================================================================
# 4. Conferencia de ponta a ponta
# ===========================================================================

Write-Passo '4/4  Conferencia'

$saude = 'sem resposta'
try {
    $j = Invoke-RestMethod -Uri $urlSaude -TimeoutSec 5
    $saude = $j.status
}
catch {
    $saude = 'sem resposta'
}

if ($saude -ne 'UP') {
    Stop-ComErro 'o /actuator/health nao diz UP' "respondeu '$saude'" `
        @("Veja $(Join-Path $DirRun 'backend.log')")
}
Write-Ok '/actuator/health = UP'

$qtdServicos = -1
try {
    $svc = Invoke-RestMethod -Uri "http://localhost:$PortaApp/api/v1/services" -TimeoutSec 10
    $qtdServicos = @($svc.data).Count
}
catch {
    $qtdServicos = -1
}

if ($qtdServicos -le 0) {
    Write-Host ''
    Write-Host "  AVISO: GET /api/v1/services devolveu $qtdServicos servicos." -ForegroundColor Yellow
    Write-Host '  A aplicacao esta de pe, mas o banco parece vazio: o seed nao rodou.' -ForegroundColor Yellow
    Write-Host '  Nao e falha do ambiente -- a grade e o painel so ficarao vazios.' -ForegroundColor Yellow
    Add-Resumo 'banco (dados)' '-' 'vazio' 'seed nao rodou'
}
else {
    Write-Ok "GET /api/v1/services devolveu $qtdServicos servicos"
    Add-Resumo 'banco (dados)' '-' 'semeado' "$qtdServicos servicos"
}

# ===========================================================================
# Resumo
# ===========================================================================

Write-Host ''
Write-Host ('=' * 62) -ForegroundColor DarkGray
Write-Host 'AMBIENTE DE PE' -ForegroundColor White
Write-Host ('=' * 62) -ForegroundColor DarkGray
$script:Resumo | Format-Table -AutoSize Servico, Porta, Estado, Nota | Out-String | Write-Host

Write-Host 'Enderecos:' -ForegroundColor White
Write-Host "  backend    http://localhost:$PortaApp" -ForegroundColor Gray
Write-Host "  banco      jdbc:postgresql://localhost:$PortaPg/$($cfg['POSTGRES_DB'])" -ForegroundColor Gray
Write-Host "  redis      redis://localhost:$PortaRd" -ForegroundColor Gray
Write-Host "  wiremock   http://localhost:$PortaWm" -ForegroundColor Gray
Write-Host ''
Write-Host 'Proximos passos:' -ForegroundColor White
Write-Host '  Frontend contra este backend:' -ForegroundColor Gray
Write-Host '    cd ..\..\projeto-agendamento-barbeiro\frontend' -ForegroundColor DarkGray
Write-Host "    `$env:VITE_API_URL = 'http://localhost:$PortaApp'; npm run dev" -ForegroundColor DarkGray
Write-Host '  Login do painel: admin@barbearia.com / Admin@123456' -ForegroundColor Gray
Write-Host '  O que o Java mandou para a "Evolution":' -ForegroundColor Gray
Write-Host "    curl.exe -s http://localhost:$PortaWm/__admin/requests" -ForegroundColor DarkGray
Write-Host "  Logs e PIDs: $DirRun" -ForegroundColor Gray
Write-Host '  Derrubar tudo: .\stop-dev.ps1' -ForegroundColor Gray
Write-Host ''
Write-Host 'Lembrete: a grade so tem horario de segunda a quinta, 08:40 as 19:30.' -ForegroundColor DarkGray
Write-Host ''
