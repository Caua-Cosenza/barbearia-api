# A borda no corte: uma Transform Rule, e nada mais

Escrito para quem vai configurar a Cloudflare no dia do corte para o backend
Java. Nada aqui foi aplicado.

> **Correção de rota.** A versão anterior deste documento descrevia três
> mudanças num Worker da Cloudflare (`cloudflare/src/index.ts`, no projeto
> Node) que escreveria um cabeçalho `X-Edge-Client-IP`. **Esse Worker não
> existe em produção** — ver [O `cloudflare/` do projeto Node nunca foi
> publicado](#o-cloudflare-do-projeto-node-nunca-foi-publicado). A topologia
> real é mais simples e exige menos: uma única Transform Rule.

## A topologia real

```
navegador  ->  api.barbeariajhonatan.com (Cloudflare proxied)  ->  Railway  ->  Spring Boot
```

O frontend chama `api.barbeariajhonatan.com` **direto**, cross-origin — não há
Worker, não há *same-origin proxy*. Esse domínio está **proxied** pela
Cloudflare, confirmado pelo `Cf-Ray` presente na resposta.

Isso muda a conta de duas formas:

1. **O IP do visitante já chega escrito.** A Cloudflare preenche
   `CF-Connecting-IP` na borda e **sobrescreve** qualquer valor que o visitante
   tenha mandado com esse nome. Não é preciso um cabeçalho nosso para
   transportar o IP.
2. **O que falta provar é só a procedência.** O serviço no Railway também
   responde em `*.up.railway.app`, que é público e contornável. Quem fala
   direto com esse endereço escreve `CF-Connecting-IP` à vontade — e é por isso
   que o backend não lê esse cabeçalho sem prova de que a requisição passou
   pela Cloudflare.

## O contrato

| Cabeçalho | Quem escreve | Conteúdo |
| --- | --- | --- |
| `X-Edge-Token` | a Transform Rule | o segredo, igual ao `EDGE_TOKEN` do backend |
| `CF-Connecting-IP` | a Cloudflare, sozinha | o IP do visitante |

A cadeia no `ClientIpResolver`:

1. **`X-Edge-Token` confere** → a chave do bucket é o `CF-Connecting-IP`.
   Nenhum outro cabeçalho é consultado.
2. **Senão** → última entrada do `X-Forwarded-For`, acrescentada pelo proxy do
   Railway: é o par que de fato abriu a conexão, e o único pedaço do cabeçalho
   que o cliente não escreveu.
3. **Senão** → `getRemoteAddr()`.

O `CF-Connecting-IP` é **validado como endereço** antes de virar chave, nos
dois casos em que é lido — qualquer coisa que não seja literal IPv4 ou IPv6 cai
no `remoteAddr`. Sem isso, texto arbitrário viraria chave no Redis e linha no
log. A comparação do token é em tempo constante (`MessageDigest.isEqual`).

### Por que a Transform Rule não escreve o IP

Tentar manter um `X-Edge-Client-IP` próprio custaria mais e valeria menos: a
Cloudflare **bloqueia** Transform Rules que modifiquem cabeçalhos de
identificação do visitante, então um cabeçalho nosso carregando o IP exigiria o
Worker de volta — e um valor dinâmico é mais frágil que o `CF-Connecting-IP`
que ela já escreve sozinha. O token é constante, e constante é tudo que uma
Transform Rule precisa escrever bem.

## A configuração

Uma regra, em **Rules → Transform Rules → Modify Request Header**, na zona
`barbeariajhonatan.com`:

| Campo | Valor |
| --- | --- |
| Nome | `Token de borda para a API` |
| Se | `Hostname` igual a `api.barbeariajhonatan.com` |
| Então | **Set static** — `X-Edge-Token` = *o segredo* |

Pontos que importam:

- **Set static**, não *Set dynamic*: o valor é um segredo constante.
- O segredo fica visível para quem tem acesso ao painel da Cloudflare. É o
  mesmo nível de exposição de uma variável no Railway; não é um segredo de
  usuário.
- A regra tem que casar **a API e só ela**. Casando o domínio do site, o token
  sairia em requisições que não vão para o backend.

## Ordem de execução no corte

1. Gerar o segredo: `openssl rand -hex 32`.
2. `EDGE_TOKEN` no Railway (serviço do backend Java), e reiniciar.
3. Criar a Transform Rule com o mesmo valor.
4. Confirmar (abaixo).
5. Só então apontar o tráfego do frontend para o backend Java.

Os passos 2 e 3 podem acontecer em qualquer ordem, porque o estado
intermediário é o *fallback* — seguro, só não utilizável em regime permanente.
O que **não** pode é o passo 5 antes dos dois.

Trocar o segredo depois exige os dois lados. Enquanto estiverem diferentes, o
token não confere e tudo cai no fallback: ninguém escolhe o próprio bucket, mas
visitantes legítimos começam a dividir poucos buckets e a tomar 429. Para girar
sem janela, o backend precisaria aceitar dois tokens durante a troca; hoje
aceita um.

## Como confirmar que funcionou

O que prova é a chave no Redis:

```bash
redis-cli --scan --pattern 'rate-limit:global:*' | head
```

Os IPs ali têm que ser de **visitantes**, variados. Se aparecerem poucos
endereços repetidos, e eles estiverem em
[`https://www.cloudflare.com/ips-v4`](https://www.cloudflare.com/ips-v4), o
token não está conferindo e o tráfego está no fallback — a regra não casou, ou
os dois valores do segredo diferem.

Um segundo teste, do lado de fora: chamar o `*.up.railway.app` direto, sem
token, e confirmar que o bucket criado é do **seu** IP, e não de um valor que
você escolheu no `CF-Connecting-IP`.

## O que isto não resolve

O `up.railway.app` continua público, e o Railway não oferece allowlist de IP na
entrada. Quem alcançar o backend direto continua passando pelo rate limit — só
que **com o próprio IP como chave**, em vez de escolhendo o bucket. É o pior
caso honesto, não o fechamento do acesso direto. Fechar de verdade exige outra
coisa (Cloudflare Tunnel, ou plataforma com allowlist na entrada) e é decisão de
infraestrutura, não de código.

## O `cloudflare/` do projeto Node nunca foi publicado

O diretório `cloudflare/` do `projeto-agendamento-barbeiro` — `wrangler.toml` e
`src/index.ts`, com D1, KV e um *same-origin proxy* — é **código que nunca foi
publicado**. Produção não tem Worker; o frontend sempre chamou a API direto,
cross-origin.

Isso é registro histórico, não pendência: o dono do projeto vai apagar esse
diretório. Está anotado aqui porque, enquanto existir, ele descreve uma
topologia plausível e errada — e foi exatamente o que levou a primeira versão
deste documento a projetar três mudanças num Worker inexistente, inclusive um
cabeçalho `X-Edge-Client-IP` que chegou a ser implementado no backend Java e
depois removido.

A lição que vale guardar: **o repositório descrevia a arquitetura que alguém
pretendeu, e a resposta HTTP descrevia a que existe.** Um `curl -I` na API,
olhando o `Cf-Ray`, teria resolvido antes.
