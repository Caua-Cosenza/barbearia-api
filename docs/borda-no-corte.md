# O que o Worker precisa passar a fazer no corte

Escrito para quem vai editar `cloudflare/src/index.ts` no projeto Node durante
o corte para o backend Java. Nada aqui foi aplicado — o projeto Node não foi
tocado.

## Por que

O backend Java deixou de confiar em qualquer cabeçalho que o cliente consiga
escrever. Ele reconhece o IP do visitante por um contrato novo, que só existe
se vier da borda:

| Cabeçalho | Conteúdo |
| --- | --- |
| `X-Edge-Token` | o segredo, igual ao `EDGE_TOKEN` do backend |
| `X-Edge-Client-IP` | o IP do visitante, lido na borda |

Sem esses dois cabeçalhos o backend cai no *fallback*: usa a última entrada do
`X-Forwarded-For`, que numa requisição vinda da Cloudflare é um **IP de saída
da Cloudflare** — igual para muitos visitantes. O fallback é seguro (ninguém
escolhe o próprio bucket), mas agrupa gente demais num bucket só: com 30
requisições por minuto por bucket, visitantes legítimos começam a tomar 429.

**Portanto a mudança do Worker entra junto com o corte, não depois.** Se o
backend Java subir antes de o Worker enviar os cabeçalhos, o rate limit fica
seguro e inutilizável ao mesmo tempo.

## As três mudanças

### 1. Construir a lista de cabeçalhos de saída do zero

Hoje o Worker repassa os cabeçalhos do cliente inteiros:

```ts
return fetch(backendUrl, {
  method: request.method,
  headers: request.headers,   // <-- tudo que o cliente mandou
  body: ['GET', 'HEAD'].includes(request.method) ? null : request.body,
})
```

Isso faz a borda carregar para dentro qualquer coisa que o cliente tenha
escrito. Partir de um objeto vazio e copiar só o que a aplicação precisa
inverte o padrão: o que passa é uma decisão explícita, não um resto.

### 2. Acrescentar os dois cabeçalhos do contrato

`X-Edge-Client-IP` sai de `CF-Connecting-IP` da requisição **de entrada** —
esse a Cloudflare sobrescreve na borda antes do Worker rodar, então ali ele não
é forjável. O token sai de uma variável de ambiente (`env.EDGE_TOKEN`,
configurada em `wrangler.toml` como secret, **nunca** no código), e precisa ser
o mesmo valor do `EDGE_TOKEN` do backend.

### 3. Descartar `X-Forwarded-For`, `X-Real-IP` e `CF-Connecting-IP` do cliente

Os três deixam de ser repassados:

- **`X-Forwarded-For`** — cabeçalho de *append*: a entrada mais à esquerda é
  escrita pelo cliente. Não repassá-lo garante que o que chegar no backend foi
  escrito só pelo proxy do Railway, que é o que o fallback assume.
- **`X-Real-IP`** — há relato (não oficial, contradiz a documentação) de que
  ele sobrescreve `CF-Connecting-IP` em subrequest de Worker para origem fora
  da Cloudflare. Descartá-lo encerra a dúvida sem depender de qual versão está
  certa.
- **`CF-Connecting-IP`** — o backend não lê mais. A Cloudflare pode
  reescrevê-lo no subrequest de qualquer forma; não repassá-lo deixa claro que
  nada depende dele.

## O resultado

```ts
/**
 * Cabeçalhos que a aplicação precisa ver. Lista de permissão e não de
 * bloqueio: cabeçalho novo do cliente não passa a atravessar a borda só
 * porque ninguém se lembrou de bloqueá-lo.
 */
const REPASSADOS = [
  'accept',
  'accept-encoding',
  'accept-language',
  'content-type',
  'content-length',
  'cookie',
  'origin',
  'referer',
  'user-agent',
]

const saida = new Headers()
for (const nome of REPASSADOS) {
  const valor = request.headers.get(nome)
  if (valor !== null) saida.set(nome, valor)
}

// O contrato da borda. CF-Connecting-IP da requisição de ENTRADA é escrito
// pela Cloudflare antes deste Worker rodar, então aqui não é forjável.
saida.set('X-Edge-Token', env.EDGE_TOKEN)
saida.set('X-Edge-Client-IP', request.headers.get('CF-Connecting-IP') ?? '')

// X-Forwarded-For, X-Real-IP e CF-Connecting-IP do cliente ficam de fora por
// construção: não estão em REPASSADOS.

return fetch(backendUrl, {
  method: request.method,
  headers: saida,
  body: ['GET', 'HEAD'].includes(request.method) ? null : request.body,
})
```

`Env` ganha o campo:

```ts
export interface Env {
  DB: D1Database
  RATE_LIMIT_KV: KVNamespace
  BACKEND_URL: string
  EDGE_TOKEN: string
}
```

E o secret:

```bash
cd cloudflare
npx wrangler secret put EDGE_TOKEN      # o mesmo valor do EDGE_TOKEN do backend
```

## Ordem de execução no corte

1. Gerar o segredo: `openssl rand -hex 32`.
2. `EDGE_TOKEN` no Railway (serviço do backend Java).
3. `EDGE_TOKEN` como secret do Worker.
4. Publicar o Worker com as três mudanças.
5. Só então apontar o tráfego para o backend Java.

Trocar o segredo depois exige os dois lados: enquanto estiverem diferentes, o
token não confere e tudo cai no fallback — seguro, e com 429 em cliente
legítimo. Para girar sem janela, o backend precisaria aceitar dois tokens
durante a troca; hoje ele aceita um.

## Como confirmar que funcionou

O que prova é a chave no Redis:

```bash
redis-cli --scan --pattern 'rate-limit:global:*' | head
```

Os IPs ali têm que ser de **visitantes**, variados. Se aparecerem poucos
endereços repetidos, e eles estiverem em
[`https://www.cloudflare.com/ips-v4`](https://www.cloudflare.com/ips-v4), o
token não está conferindo e o tráfego está no fallback.

## O que isto não resolve

O `up.railway.app` continua público, e o Railway não oferece allowlist de IP na
entrada. Quem alcançar o backend direto continua passando pelo rate limit — só
que agora **com o próprio IP como chave**, em vez de escolhendo o bucket. É o
pior caso honesto, não o fechamento do acesso direto. Fechar de verdade exige
outra coisa (Cloudflare Tunnel, ou plataforma com allowlist na entrada) e é
decisão de infraestrutura, não de código.
