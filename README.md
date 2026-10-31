# Barbearia API

[![CI](https://github.com/Caua-Cosenza/barbearia-api/actions/workflows/ci.yml/badge.svg)](https://github.com/Caua-Cosenza/barbearia-api/actions/workflows/ci.yml)

Backend de agendamento da **Barbearia Jhonatan Correa**, escrito em Java com
Spring Boot.

Este serviço substitui, de forma incremental, um backend Node (Fastify +
TypeScript + Prisma) que já roda em produção atendendo clientes reais todos os
dias. A substituição acontece **sem tocar no frontend**: o aplicativo React em
produção continua o mesmo, consumindo os mesmos caminhos `/api/v1/*` e o mesmo
formato de resposta. Na prática, isso significa que o frontend é o critério de
sucesso da migração — se ele percebe a troca, a migração falhou.

Durante a transição os dois backends leem e escrevem o **mesmo banco
PostgreSQL**. Essa é a restrição mais importante do projeto e explica várias
decisões que, fora desse contexto, pareceriam estranhas (ver
[Decisões técnicas](#decisões-técnicas)).

> **Status:** Fase 1 de 7 concluída — estrutura, containerização e migrations.
> Entidades, regras de negócio e endpoints entram nas fases seguintes.

---

## Stack

| Camada | Tecnologia |
| --- | --- |
| Linguagem | Java 21 |
| Framework | Spring Boot 4.1.2 (Spring Framework 7, Jakarta EE 11) |
| Build | Maven (via `mvnw`) |
| Banco | PostgreSQL 18 (mesma versão de produção) |
| Migrations | Flyway |
| Cache / rate limit | Redis 7 + Bucket4j |
| Persistência | Spring Data JPA (Hibernate) |
| Segurança | Spring Security + JJWT |
| Observabilidade | Spring Boot Actuator |
| Testes | JUnit 5, AssertJ, Testcontainers |
| Cobertura | JaCoCo |
| Containers | Docker multi-stage + Docker Compose |

---

## Contrato de resposta

Toda resposta da API — inclusive as de erro — usa o mesmo envelope, herdado do
backend Node:

```json
{
  "success": true,
  "data": {},
  "error": null,
  "message": "Operação realizada com sucesso",
  "timestamp": "2026-10-25T18:30:00.000Z"
}
```

Em caso de erro, `success` é `false`, `data` é `null` e `error` traz o código do
erro. O envelope nunca muda de forma, porque o frontend em produção depende
dele.

O `timestamp` reproduz exatamente o `new Date().toISOString()` do JavaScript:
UTC, sempre com três casas de milissegundo, sempre terminando em `Z`. Ver
`JacksonConfig`.

---

## Endpoints públicos

Os caminhos são os do `src/api/endpoints.ts` do frontend, que é o contrato.

| Método | Caminho | O que faz |
| --- | --- | --- |
| GET | `/api/v1/services` | catálogo ativo, com `amountCents` |
| GET | `/api/v1/professionals` | profissionais ativos, com `availability` embutida |
| POST | `/api/v1/available-times` | horários livres de um dia |
| POST | `/api/v1/appointments/public` | reserva de visitante (201) |
| GET | `/api/v1/cancel/{token}` | dados da tela de cancelamento |
| POST | `/api/v1/cancel/{token}` | efetiva o cancelamento |

Rotas de login, de `/admin/*` e `POST /api/v1/appointments` (cliente logado)
são da Fase 6 e hoje respondem negado.

### Erros

| Status | Quando |
| --- | --- |
| 400 | regra de negócio sobre entrada bem formada — dia sem jornada, fora do expediente, prazo de cancelamento vencido |
| 404 | recurso inexistente **ou inativo** |
| 409 | horário já ocupado, ou bloqueado pelo profissional |
| 422 | falha de validação de entrada (equivalente ao `ZodError` do Node) |
| 429 | rate limit |
| 500 | defeito — resposta genérica, nada da stack |

### Rate limit

Por IP, com os valores do Node: **5/min** em `POST /api/v1/auth/**`,
**10/min** em `POST /api/v1/appointments/**`, **30/min** em
`POST /api/v1/available-times`, **30/min** no resto. `/actuator/health` fica de
fora. Os baldes são independentes por rota.

---

## Autenticação e rotas de admin

Login por **JWT em cookies `HttpOnly`** — o JavaScript do painel nunca vê um
token. Daí o `AuthContext` chamar `GET /admin/health` no boot: é o único jeito
de descobrir se há sessão.

| Método | Caminho | O que faz |
| --- | --- | --- |
| POST | `/api/v1/auth/login` | grava os dois cookies |
| POST | `/api/v1/auth/refresh` | rotaciona o par de tokens |
| POST | `/api/v1/auth/logout` | limpa os cookies e revoga o refresh |
| GET | `/api/v1/admin/health` | teste de sessão (401 é resposta normal) |
| GET | `/api/v1/admin/stats` | os quatro contadores |
| GET | `/api/v1/admin/appointments?date=` | agenda do dia, todos os status |
| PUT | `/api/v1/admin/appointments/{id}/status` | finalizar / cancelar / no-show |
| POST / GET / DELETE | `/api/v1/admin/blocked-slots` | reservar horário |
| POST | `/api/v1/admin/push-subscription` | inscrição Web Push |

Tudo em `/admin/**` exige `ROLE_ADMIN`. Uma conta `CLIENT` autentica e recebe
**403**, não 401 — mandar 401 faria o painel redirecionar para o login num
laço infinito, já que logar de novo daria o mesmo papel.

### Os dois tokens

|  | access | refresh |
| --- | --- | --- |
| segredo | `JWT_SECRET` | `JWT_REFRESH_SECRET` |
| claims | `sub` + `role` | `sub` + `jti` |
| validade | 15 minutos | 7 dias |
| cookie `Path` | `/` | `/api/v1/auth/refresh` |

Os segredos são diferentes de propósito: um access token vazado não vira
refresh token. O refresh **não carrega `role`** — o papel é relido do banco a
cada renovação, então quem for rebaixado perde o acesso em no máximo 15
minutos em vez de carregar o papel antigo por sete dias.

O `Path` restrito do refresh significa que ele não trafega em nenhuma das
dezenas de requisições que o painel faz: um log de proxy mal configurado expõe
o token de 15 minutos, não a credencial de sete dias.

**Rotação:** cada refresh apaga o token usado e grava um novo. Um refresh token
serve uma vez só.

### O hash do refresh token é SHA-256, não bcrypt

A coluna `refresh_tokens.token_hash` tem índice único e a validação é uma busca
exata por esse hash. Bcrypt tem salt aleatório, produz hash diferente a cada
chamada e tornaria a busca impossível. SHA-256 serve aqui porque a entrada é um
JWT de alta entropia, não uma senha — não há dicionário para atacar. É o que o
`authService.ts` faz (`createHash('sha256').digest('hex')`).

É essa tabela que dá **efeito real** ao logout: um JWT não pode ser desfeito, e
é a ausência da linha que faz um token revogado parar de servir.

### As flags dos cookies

| flag | dev | prod |
| --- | --- | --- |
| `HttpOnly` | \<-- sempre --> | |
| `Secure` | false | true |
| `SameSite` | `Strict` | `None` |
| `Domain` | ausente | `.barbeariajhonatan.com` |

**O ponto na frente do domain não é detalhe.** Sem ele o Safari do iPhone
descarta o cookie e o painel não loga no celular — funciona em todo navegador
de desktop, o que torna o diagnóstico caro.

`SameSite=None` e `Secure` andam juntos: `None` sem `Secure` é recusado pelos
navegadores. `None` é necessário porque front e API ficam em subdomínios
diferentes em produção; em dev os dois lados são `localhost` (mesmo site,
portas diferentes) e `Strict` funciona.

Os cookies de limpeza repetem `Domain`, `Path`, `Secure` e `SameSite` do
original. O navegador identifica um cookie pela trinca *nome + domain + path*:
um `Set-Cookie` de expiração que erre qualquer um dos três cria um **segundo**
cookie vazio e deixa o original de pé — o usuário clica em sair e continua
logado.

### CSRF desabilitado — e o que sustenta isso

Com o JWT em cookie a API é, em princípio, vulnerável a CSRF. O que sustenta a
decisão: toda rota de escrita exige `Content-Type: application/json`, o que
**obriga o preflight** CORS, e o preflight é recusado para qualquer origem fora
de `CORS_ORIGIN`. Um formulário HTML, que escaparia do preflight, só envia
`x-www-form-urlencoded`, que esta API não aceita.

Vale anotar como dívida: `SameSite=None` em produção tira a camada que o
navegador daria de graça, e a defesa fica inteiramente no CORS. Um token CSRF
por sessão seria mais robusto e exigiria mudar o frontend.

---

## Como rodar localmente

Pré-requisitos: **Docker** e **Docker Compose**. Não é preciso ter Java nem
Maven instalados — o build acontece dentro da imagem.

```bash
# 1. Crie seu arquivo de variáveis a partir do exemplo
cp .env.example .env

# 2. Gere os segredos e cole no .env
openssl rand -base64 48   # JWT_SECRET e JWT_REFRESH_SECRET
openssl rand -hex 32      # ENCRYPTION_KEY (exatamente 64 caracteres hex)

# 3. Suba tudo
docker compose up --build
```

A API fica em `http://localhost:8080`.

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP",...}
```

Para parar e descartar os volumes:

```bash
docker compose down -v
```

### Rodando sem Docker

Precisa de Java 21 e de um PostgreSQL e um Redis acessíveis. Com as variáveis
do `.env` exportadas no ambiente:

```bash
./mvnw spring-boot:run
```

### Testes

```bash
./mvnw test              # sobe Postgres e Redis via Testcontainers
./mvnw verify            # testes + relatório JaCoCo em target/site/jacoco/
```

---

## Variáveis de ambiente

As obrigatórias são verificadas no startup por
`config/EnvironmentValidator`. Se faltar alguma, a aplicação **recusa subir** e
lista de uma vez tudo o que está errado, citando apenas o nome da variável e o
motivo — nunca o valor. Ver `.env.example` para a lista completa.

| Variável | Obrigatória | Regra |
| --- | --- | --- |
| `DATABASE_URL` | sim | URL JDBC do PostgreSQL |
| `DB_USERNAME` / `DB_PASSWORD` | sim | credenciais do banco |
| `REDIS_URL` | sim | ex. `redis://localhost:6379` |
| `JWT_SECRET` | sim | mínimo 32 caracteres |
| `JWT_REFRESH_SECRET` | sim | mínimo 32 caracteres |
| `ENCRYPTION_KEY` | sim | exatamente 64 caracteres hex (AES-256) |
| `CORS_ORIGIN` | sim | uma ou mais URLs http/https, separadas por vírgula |
| `EDGE_TOKEN` | só em `prod` | mínimo 32 caracteres; segredo compartilhado com a borda |
| `FRONTEND_URL` | não | |
| `EVOLUTION_API_URL` | não | API de WhatsApp |
| `EVOLUTION_API_KEY` | não | API de WhatsApp |
| `EVOLUTION_INSTANCE_NAME` | não | API de WhatsApp |
| `BARBER_PHONE` | não | telefone que recebe as notificações |

### Perfis

| Perfil | Comportamento |
| --- | --- |
| `dev` | SQL logado, CORS liberado para `http://localhost:5173` |
| `test` | datasource e Redis fornecidos pelo Testcontainers, Flyway ativo |
| `prod` | SQL não logado, log raiz em `WARN`, CORS vindo do ambiente, sem stack trace nas respostas |

---

## Estrutura de pastas

```
src/main/java/com/cauacosenza/barbearia/
├── BarbeariaApplication.java   ponto de entrada
├── domain/
│   ├── entity/                 entidades JPA — o mapeamento do banco
│   ├── enums/                  Role, AppointmentStatus etc.
│   ├── rules/                  regras de negócio PURAS (ver abaixo)
│   └── exception/              exceções de domínio
├── repository/                 interfaces Spring Data
├── service/                    orquestração: transação, cache, notificação
├── controller/                 endpoints REST /api/v1/*
├── dto/
│   ├── request/                o que entra (com validação Bean Validation)
│   └── response/               o que sai (no envelope do contrato)
├── integration/                clientes HTTP externos (Evolution API)
├── validation/                 constraints de Bean Validation próprias
├── security/                   JWT, cookies, filtro de autenticação, cifra de PII
├── domain/event/               eventos de domínio (notificação após commit)
├── config/                     security, CORS, Redis, rate limit, validação de ambiente
└── exception/                  tratamento global de erro → envelope de erro

src/main/resources/
├── application.yml             configuração dos três perfis
└── db/migration/               migrations Flyway (V1__schema_inicial.sql ...)

src/test/java/com/cauacosenza/barbearia/
├── domain/rules/               testes unitários das regras puras (sem Spring)
├── security/                   testes do CryptoService (sem Spring)
├── service/                    testes do mapper (sem Spring, sem banco)
├── controller/                 testes de borda HTTP, services mockados
└── integration/                testes contra Postgres real (Testcontainers)
```

### A regra do pacote `domain/rules`

`domain/rules` **não importa nada de Spring, de JPA ou de Jakarta**. São classes
puras: recebem listas, devolvem listas.

É onde mora o cálculo de horários disponíveis — a parte mais delicada do
sistema, que combina jornada semanal do profissional, agendamentos existentes,
bloqueios pontuais e duração do serviço. Isolar esse cálculo significa que ele é
testável com dezenas de casos de borda em milissegundos, sem subir contexto
nenhum nem banco nenhum.

A restrição vale para o projeto inteiro, não só para a fase atual.

---

## Decisões técnicas

<!--
  Esta seção é preenchida ao longo das fases 2 a 7. Cada entrada registra uma
  decisão e, principalmente, POR QUE ela foi tomada — o contexto é o que se
  perde primeiro.
-->

### PII cifrada por `AttributeConverter` — e o que isso custa

As colunas `*_encrypted` guardam AES-256-CBC no formato do backend Node
(`hex(iv):hex(ciphertext)`). Em vez de cifrar e decifrar à mão em cada serviço,
`security/EncryptedStringConverter` fica no caminho entre a entity e a coluna:
a entity tem um `String` em texto claro e o banco nunca vê o texto claro.

O converter é um bean Spring e recebe o `CryptoService` por construtor. Isso
funciona porque o Spring Boot registra o `SpringBeanContainer` como
`hibernate.resource.beans.container`, então o Hibernate pede a instância ao
container do Spring em vez de instanciar a classe por conta própria — sem
`ApplicationContextAware` e sem holder estático. Se o wiring quebrar, quebra no
boot.

Nulo atravessa sem passar pelo `CryptoService`, porque o `decrypt(null)` da
Fase 2 lança `CryptoException` de propósito e as colunas `guest_*_encrypted`
são NULLABLE. String vazia **não** é nulo: é cifrada normalmente.

**A consequência, que é direta e não tem como contornar:** o IV é novo a cada
gravação, então o mesmo nome produz bytes diferentes toda vez. **Não existe
`WHERE`, `LIKE` nem `ORDER BY` em coluna cifrada.** Buscar ou ordenar por PII
exige carregar as linhas e decifrar em memória. Na prática não incomoda — o
sistema não procura cliente por nome, e `users.email`, que é a credencial de
login e precisa de índice único, foi deixado em texto claro justamente por
isso. Mas é escolha, não acidente: cifrar o e-mail quebraria o login.

### A duração do atendimento é materializada em `slot_duration_minutes`

Uma constraint de exclusão precisa de expressão **imutável** sobre a própria
linha. A duração efetiva do atendimento é a soma de `appointment_services`, em
outra tabela — uma subconsulta não é imutável e não entra no índice. Daí a
coluna `slot_duration_minutes`, que a V2 acrescentou a `appointments`.

A regra é uma só, aplicada em três lugares que têm de concordar: soma das
durações dos itens, **teto de 50 minutos**, **fallback de 30** quando não há
item nenhum (agendamentos anteriores à tabela `appointment_services`). Os três
lugares são o backfill da V2, o `SlotQueryMapper.slotDurationMinutes` na
leitura, e o preenchimento da coluna na escrita. Se divergirem, a tela oferece
um horário que a constraint vai recusar no commit — erro aparentemente
aleatório na reserva, o pior tipo de inconsistência.

### V2: a garantia contra duplo agendamento está no schema, não no código

Verificar conflito antes de inserir deixa uma janela entre a checagem e o
insert, e é o que o backend Node faz hoje. Fechar a janela no código exigiria
lock pessimista por profissional em toda reserva, serializando a operação mais
frequente do sistema. O Postgres resolve na definição do schema:

```sql
EXCLUDE USING gist (
  professional_id WITH =,
  tsrange(scheduled_at, scheduled_at + make_interval(mins => slot_duration_minutes)) WITH &&
) WHERE (status IN ('PENDING','CONFIRMED'))
```

Detalhes que foram escolhidos e não herdados:

- **`tsrange`, não `tstzrange`** — `scheduled_at` é `TIMESTAMP(3)` *sem* fuso.
  Passar um timestamp sem fuso para `tstzrange()` forçaria conversão pelo
  `TimeZone` da sessão, que depende do cliente: não é imutável e o Postgres
  recusa a expressão no índice.
- **`make_interval(mins => ...)`, não `(coluna || ' minutes')::interval`** — o
  cast de texto para interval depende de `IntervalStyle`; `make_interval` é
  imutável por construção.
- **O range é `[início, fim)`** — por isso agendamentos encostados (um termina
  10:50, o outro começa 10:50) não conflitam. Sem isso a grade de 50 em 50 que
  o `SlotCalculator` gera não caberia no próprio dia.
- **`WHERE` parcial** — cancelado, concluído e no-show saem do índice e o
  horário volta a ser vendável. É o mesmo conjunto de
  `AppointmentStatus.ocupaAgenda()` e do filtro de
  `AppointmentRepository.buscarQueOcupamAgenda`.

#### ⚠️ A V2 **não** foi aplicada em produção

Dois motivos, nenhum deles opcional:

1. Ela altera um banco que o Node usa **agora**. `slot_duration_minutes` entra
   `NOT NULL` sem default, e todo `INSERT` do Prisma passaria a falhar — o
   Prisma não conhece a coluna.
2. O start script do Node roda `prisma db push`, que sincroniza o banco com o
   `schema.prisma`. Como o Prisma não conhece nem a coluna nem a constraint, o
   próximo deploy do Node as removeria em silêncio.

Aplicar a V2 em produção é parte do **plano de corte**, junto com a remoção
daquele `db push`. Até lá ela vive no ambiente de teste, onde é o que prova que
a garantia funciona. O backfill rodou sem encontrar nenhuma sobreposição
preexistente no banco de teste.

### O perdedor da corrida perde de duas formas — a Fase 5 precisa tratar as duas

Com duas threads disputando o mesmo horário a partir de um `CountDownLatch`
comum, exatamente uma vence. Mas o perdedor recebe **uma de duas exceções**, e
qual delas depende de escalonamento:

| Situação | Exceção |
| --- | --- |
| O vencedor **já comitou** quando o perdedor chega ao índice | `DataIntegrityViolationException`, citando `appointments_sem_sobreposicao` |
| As duas transações chegam ao índice **ainda abertas** | `CannotAcquireLockException` — *"deadlock detected ... while checking exclusion constraint"* |

O segundo caso é comportamento normal do Postgres e não um defeito: ao
encontrar uma tupla não comitada que conflita, a checagem da constraint de
exclusão **espera** pela transação concorrente, porque não há como saber se
aquela linha vai existir. As duas esperam uma pela outra e o detector de
deadlock mata uma.

Nos dois caminhos o resultado observável é o mesmo e é o que importa: **uma
linha no banco, nenhum duplo agendamento**. Mas a camada de serviço da Fase 5
**não pode tratar apenas `DataIntegrityViolationException`** — um deadlock
vazaria como erro 500 em vez de "esse horário acabou de ser ocupado". Essa
variante só apareceu depois de repetir o teste 20 vezes; na primeira execução
isolada ela não acontece.

### Mapeamentos que o `ddl-auto: validate` cobra

O schema é do Node e intocável, então as entities se adaptam. Quatro pontos em
que o padrão do Hibernate não serve:

- **`professional_availability` tem colunas em camelCase** (`"dayOfWeek"`,
  `"startTime"`, `"endTime"`), porque esses campos nunca foram mapeados no
  `schema.prisma`. Exigem `@Column(name = "\"dayOfWeek\"")` com aspas
  escapadas: sem elas o Hibernate gera `dayofweek` e a coluna não é
  encontrada. Contraste com `blocked_slots`, que usa `start_time`/`end_time` em
  snake_case — a inconsistência está em produção e não pode ser corrigida.
- **`id` é TEXT, não `uuid`** — `String` com `@GeneratedValue(strategy = UUID)`,
  o UUID gerado na aplicação, como o Prisma faz.
- **Horas e datas são TEXT** (`"HH:MM"`, `"YYYY-MM-DD"`), não `TIME`/`DATE`.
  Ficam `String` na entity; a conversão para `LocalTime`/`LocalDate` é do
  `SlotQueryMapper`. Mapear como `LocalTime` faria o `validate` exigir uma
  coluna `time`.
- **`TIMESTAMP(3)` sem fuso guardando UTC** — `Instant` com
  `@JdbcTypeCode(SqlTypes.TIMESTAMP)`, somado ao
  `hibernate.jdbc.time_zone: UTC`. Sem forçar o código JDBC, o Hibernate
  recomenda `TIMESTAMP_UTC` para `Instant`, pediria `timestamptz` e o
  `validate` reprovaria.

### Enum do Postgres: literal em JPQL não funciona neste banco

Os tipos `"AppointmentStatus"` e `"Role"` foram criados pelo Prisma em
camelCase, com aspas. As entities usam
`@Enumerated(STRING) + @JdbcTypeCode(SqlTypes.NAMED_ENUM)`, que envia o valor
como tipo desconhecido e deixa o Postgres resolver pela coluna.

O que **não** funciona é citar o enum como literal em JPQL. Escrever
`where a.status in (AppointmentStatus.PENDING, ...)` parece mais direto, mas o
Hibernate inlina como `'PENDING'::AppointmentStatus` — **sem aspas no nome do
tipo** — e o Postgres rebaixa para `appointmentstatus`, que não existe:

```
ERROR: type "appointmentstatus" does not exist
```

Por isso os status vão **por parâmetro** (`in :statuses`), com o conjunto
`OCUPAM_AGENDA` injetado por um `default method` do repositório. O binder de
`NAMED_ENUM` não cita o nome do tipo em lugar algum, e a regra de negócio
continua dentro do repositório, fora do alcance de quem chama.

### Stats: onde o fuso e a semana mordem

Todo recorte de data é em **America/Sao_Paulo**, nunca em UTC. Uma versão
anterior do Node usava `toISOString()` para achar "hoje" e, depois das 21h —
quando em UTC já é o dia seguinte — trabalhava com a data errada: bloqueios
legítimos eram recusados por "estar no passado".

O dia vai de `00:00:00.000` a `23:59:59.999` em Brasília, com **os dois limites
inclusivos** (`gte`/`lte`), diferente do `[início, fim)` do cálculo de
horários. A diferença importa para os números baterem com os do Node.

**A semana vai de terça a sábado**, não de domingo a sábado: é a semana de
trabalho da barbearia. Consequência que parece bug e não é — consultado num
**domingo ou numa segunda, o contador mostra a semana que acabou de
terminar**, porque o cálculo anda para trás até a terça mais recente e nesses
dois dias ela pertence à semana anterior.

Os três primeiros contadores somam `PENDING`, `CONFIRMED` e `COMPLETED`;
cancelado e no-show não são atendimento.

#### ⚠️ `noShowCount` é acumulado desde sempre

Divergência conhecida do Node, **reproduzida de propósito**: os outros três
contadores são por período, mas o de no-shows é
`count({ where: { status: 'NO_SHOW' } })`, sem recorte nenhum. O número só
cresce, para sempre.

Não foi corrigido porque corrigir mudaria um número que o dono vê todo dia, e
essa é decisão dele, não desta migração. Entra na lista de trabalho futuro.

### Blocked slots: compatibilidade antes de correção

**Criar bloqueio sobre horário já agendado continua permitido**, e isso é
deliberado. O comportamento correto seria recusar com 409 e a lista dos
clientes afetados, exigindo confirmação explícita — mas o painel React em
produção não sabe tratar esse 409: ele faz `if (res.success)` e, no catch,
apenas mostra "Erro ao criar bloqueio". Implementar a melhoria agora deixaria
o Jhonatan **sem conseguir reservar horário nenhum**, que é pior que o furo.

Na prática o efeito é limitado: o bloqueio tira o horário da lista pública mas
**não cancela** o agendamento existente, que continua no painel do dia. O dono
vê os dois e resolve por telefone.

A melhoria entra depois do corte, junto com a mudança no frontend.

### O contrato é o frontend, não a documentação

Onde a descrição da fase e o código divergiram, o código venceu. As
divergências encontradas ao ler `endpoints.ts`, `types/index.ts` e os
controllers do Node:

| O que se supunha | O que o código diz |
| --- | --- |
| `POST /api/v1/appointments` cria reserva de visitante | é **`/appointments/public`**; `/appointments` é a rota de cliente logado, com corpo diferente (`professional_id` em snake_case, um serviço só) — Fase 6 |
| `GET/POST /api/v1/appointments/cancel/{token}` | é **`/cancel/{token}`**, com prefixo próprio |
| erro de validação → 400 | o `errorHandler.ts` devolve **422** para `ZodError` |
| prazo de cancelamento → 422 | o Node devolve **400** |
| agendar no passado → 400 | **422**: o `.refine` do Zod roda antes do service e sombreia o `if` dele. A mesma assimetria foi reproduzida — ver `AgendamentoNoPassadoException` |
| push subscription seria rota pública | é `POST /api/v1/admin/push-subscription`, atrás de autenticação — Fase 6 |

E as da Fase 7:

| O que se supunha | O que o código diz |
| --- | --- |
| duas mensagens no fluxo vivo | são **três**: confirmação ao cliente, aviso de novo agendamento ao barbeiro, e **aviso de cancelamento ao barbeiro** (de `routes/cancel.ts`) |
| NoOp quando falta `EVOLUTION_API_URL` | o Node checa **as três** variáveis: URL, API key e instância |
| o Node resolve a resiliência com try/catch | `try/catch` em volta do `fetch` **mais** promessa solta (`void`, sem `await`) depois de a transação fechar |

E as da Fase 6:

| O que se supunha | O que o código diz |
| --- | --- |
| `PATCH /api/v1/admin/appointments/{id}` | é **`PUT .../{id}/status`**, com corpo `{ status }` |
| só login, refresh e logout em `/auth` | havia também `POST /auth/register`, que nenhuma tela chamava — **removido**, ver abaixo |
| o refresh token seria hasheado | é **SHA-256 hex**, não bcrypt — e por um bom motivo (ver acima) |
| no-shows talvez por período | é **acumulado desde sempre**, confirmado |

Três detalhes a mais:

- **`/admin/health` responde `{"status":"admin ok"}`**, não `"ok"`, com
  `message: "Admin route active"`.
- **`GET /admin/blocked-slots` exige `date` **e** `professionalId`**, os dois
  obrigatórios — diferente de `GET /admin/appointments`, onde `date` é
  opcional.
- **O status `PENDING` não é aceito** no `PUT .../status`: o
  `UpdateStatusSchema` do Node lista só `CONFIRMED`, `CANCELLED`, `COMPLETED` e
  `NO_SHOW`. O admin não devolve um agendamento ao estado inicial.

#### `POST /auth/register` existia e foi removido

Tinha sido portado para manter a equivalência com o Node, e **nenhuma tela o
chamava**: o painel tem um único administrador, criado pelo seed, que grava a
linha direto via Prisma. Foi medido antes de sair — respondia **201 sem
autenticação nenhuma**, criando conta `CLIENT`. Como `CLIENT` não alcança
`/admin`, não era escalada de privilégio; era escrita não autenticada em
`admin_users`, de um tipo de conta que o sistema não consome (o agendamento é
público, sem conta).

Saíram juntos o método do `AuthController`, o `AuthService.registrar`, o
`RegisterRequest`, a `EmailJaCadastradoException`, a linha de `permitAll()` no
`SecurityConfig` e o `registerSchema`/`RegisterForm` órfãos do frontend.

A ausência tem teste: `RegistroFoiRemovidoTest` afirma que o caminho responde
**exatamente igual** a um que nunca existiu — 401 do `anyRequest().denyAll()`,
e não 404, porque a cadeia do Spring Security roda antes do roteamento.

Dois detalhes que só aparecem lendo o código com atenção:

- **`GET /services` devolve `amountCents`.** Há um comentário em
  `serviceService.listAll()` dizendo que o preço não sai nessa rota. O
  comentário está errado: o `select` do repositório inclui `amountCents`, e
  `ServicesList.tsx` e `BookingModal.tsx` dependem dele para mostrar preço.
  Removê-lo faria todos os preços virarem "Consultar".
- **`pages/Booking.tsx` é código morto.** Ela monta `scheduledAt` **sem fuso**
  (`${date}T${timeSlot}:00`), que o `z.string().datetime({offset:true})`
  rejeita — confirmado rodando o Zod do próprio projeto. A tela não está
  roteada em `App.tsx`; o fluxo vivo é `Home` → `BookingModal`, que manda
  `-03:00`. A API nova recusa o formato sem fuso, igual ao Node.

### O slot precisa caber inteiro no expediente — correção em relação ao Node

O `createPublic` do Node só verificava o **início**:
`requestedStart >= endMin` recusava. Com expediente até 19:30, um pedido das
19:20 com 50 minutos passava, e o atendimento terminava **20:10**.

A leitura nunca ofereceu esse horário — o laço do `SlotCalculator` avança
enquanto `início + duração <= fim` — então as duas pontas discordavam e a
brecha só era alcançável chamando a API direto. É o mesmo tipo de furo da
auditoria que encontrou "agendar domingo às 03:00 pela API". Agora a escrita
exige o mesmo que a leitura, e a mensagem cita os dois horários.

As outras sete validações de criação (futuro, profissional ativo, jornada no
dia, dentro do expediente, sem colisão com bloqueio, serviços ativos, duração
`min(soma, 50)`) reproduzem o Node. **Nenhuma delas é a garantia final**: entre
verificar e inserir há uma janela, e quem a fecha é a constraint de exclusão da
V2. A divisão é explícita — as validações dão a mensagem boa, o banco dá a
certeza.

### As duas faces da corrida perdida chegam ao cliente como 409

A descoberta da Fase 4 vira requisito aqui. O `PublicAppointmentService`
traduz **`DataIntegrityViolationException` e `CannotAcquireLockException`** para
a mesma `SlotIndisponivelException` → 409 com mensagem de negócio.

Traduzir só a primeira deixaria o deadlock cair no catch-all como **500 "Erro
inesperado" em 18 de cada 20 disputas reais** por horário. `ConcurrentBookingHttpTest`
roda 20 iterações de duas requisições HTTP simultâneas e afirma, em todas:
uma 201, uma 409 com a mensagem certa, uma linha no banco, **e nenhum 5xx**.

### Rate limit por IP — e por que isso diverge do Node

Os limites são os mesmos do Node (10/30/30 por minuto), sobre o Redis que já
estava configurado, com Bucket4j. A diferença está na **chave**.

O Node usa `keyGenerator: (req) => req.ip`, mas o Fastify só popula `req.ip` a
partir dos cabeçalhos de proxy quando `trustProxy` está ligado — e o
`index.ts` cria a instância com `Fastify({ logger: false })`, sem ligar. Atrás
do Cloudflare e do Railway, `req.ip` é o endereço do **proxy**: na prática
**todos os clientes do Node compartilham um único balde**. Quem limita por IP
de verdade hoje é a regra do Cloudflare, que usa `ip.src` na borda.

### Como o IP do cliente é resolvido

O `ClientIpResolver` usa, nesta ordem:

1. **`X-Edge-Client-IP`, se o `X-Edge-Token` conferir** com o `EDGE_TOKEN`
   configurado. É a borda se identificando; nenhum outro cabeçalho é lido.
   Comparação em tempo constante (`MessageDigest.isEqual`).
2. **A última entrada do `X-Forwarded-For`** — o par que de fato abriu a
   conexão com a borda do Railway.
3. **`getRemoteAddr()`**.

O valor é validado como literal de IP (v4 ou v6) antes de virar chave de
bucket; o que não for cai para o passo seguinte. Com isso o valor logado pelo
`RateLimitFilter` é **sempre** um IP.

A primeira versão confiava em `CF-Connecting-IP` e, na falta dele, na
**primeira** entrada do `X-Forwarded-For`. Os dois são escolhidos pelo cliente
quando a requisição não passa pela borda — e o `up.railway.app` é público, o
Railway não oferece allowlist de IP na entrada, então sempre existe um caminho
que não passa pela borda. Quem descobrisse isso trocava o cabeçalho a cada
requisição e tinha bucket novo a cada requisição: rate limit inexistente,
inclusive o bucket de 5/min do `/auth`, que é a trava de força bruta de senha.
A entrada mais à esquerda do `X-Forwarded-For` era o pior dos dois, porque num
cabeçalho de *append* ela é exatamente a parte que o cliente escreveu.
`CF-Connecting-IP` deixou de ser lido: com o contrato da borda ele é
redundante, e a aplicação passou a não olhar nenhum cabeçalho que o cliente
consiga escrever. Os cenários estão em `ResolucaoDeIpDoClienteTest`.

> **O passo 2 é seguro, não é regime permanente.** Sem `EDGE_TOKEN` — ou
> enquanto a borda não enviar os dois cabeçalhos — a última entrada do
> `X-Forwarded-For` de uma requisição vinda da Cloudflare é um IP de saída
> **da Cloudflare**, igual para muita gente, e os visitantes voltam a dividir
> poucos buckets. A mudança do Worker tem que entrar junto com o corte, não
> depois.

### Jackson 3, não Jackson 2

O Spring Boot 4 trocou `com.fasterxml.jackson.databind` por
`tools.jackson.databind`. As anotações continuam em
`com.fasterxml.jackson.annotation`, o que torna fácil importar o pacote errado
e receber "cannot be resolved" num projeto em que o Jackson obviamente existe.
Os tipos mudaram de nome junto: `JsonSerializer` → `ValueSerializer`,
`SerializerProvider` → `SerializationContext`,
`Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer`.

### Ordem dos campos no catálogo

Nenhuma das consultas do Node tem `orderBy`; o Postgres devolve na ordem do
heap. Como o seed cria os serviços na ordem em que o dono quer vê-los na home —
os cortes primeiro, os acabamentos no fim — ordenar por nome jogaria
"Acabamento / Pé" para o topo e reorganizaria a página sem que ninguém tivesse
pedido. Daí `ORDER BY created_at`: reproduz o que o Node entrega hoje e, ao
contrário do heap, continua reproduzindo depois de um `UPDATE`.

### Os testes de integração não são `@DataJpaTest`

`@DataJpaTest` envolve cada teste em uma transação com rollback. Uma constraint
de exclusão é verificada **no commit**, e uma condição de corrida entre threads
exige commits reais em conexões distintas — nenhum dos dois cabe dentro de uma
transação de teste. Os testes herdam de `AbstractPostgresIntegrationTest`, que
sobe o contexto inteiro (o que também faz o `ddl-auto: validate` rodar de graça
em cada classe), não é `@Transactional`, e limpa as tabelas com
`TRUNCATE ... CASCADE` antes de cada teste.

As anotações são idênticas em todas as subclasses de propósito: o Spring
cacheia o contexto por conjunto de anotações, então os containers de Postgres e
Redis sobem uma vez para a suíte toda.

Os testes de borda HTTP usam `@MockitoBean` e **não** um `@Bean` `@Primary`. A
primeira versão usava `@Primary` e estava silenciosamente errada: o campo
`@Autowired` do teste recebia o bean real (envolto no proxy CGLIB da
transação), então `given(service.metodo(any()))` **executava o método de
verdade** ao montar o stub, e o teste falhava com a exceção da regra de negócio
em vez de testar o mapeamento dela.

### Equivalência verificada contra o Node

Os dois backends foram postos no ar ao mesmo tempo, apontando para o **mesmo
banco** (um `barbearia_cmp` criado só para isso, populado pelo seed real do
Prisma), e as respostas foram comparadas campo a campo, ignorando apenas o
`timestamp`:

- `GET /services` e `GET /professionals` — idênticos;
- `POST /available-times` em 12 cenários (agenda vazia e carregada, durações de
  10/20/50/90 min e sem `totalDurationMinutes`, os sete dias da semana,
  profissional de walk-in) — idênticos;
- os caminhos de erro (profissional e serviço inexistentes, UUID inválido, data
  malformada, corpo vazio) — mesmo status, mesmo `error`, mesma `message`.

Duas correções saíram dessa comparação: `@NotNull(message = "Required")` no
lugar de `@NotBlank`, para que campo ausente produza o mesmo texto do Zod; e a
ordenação dos erros de validação **pela ordem de declaração do record**, que é
a ordem do schema Zod — o Hibernate Validator entrega as violações em ordem
imprevisível, então alguma ordenação era obrigatória, e essa custa o mesmo que
a alfabética e bate com o Node.

Ao rodar os dois juntos: **nunca use `npm start` no Node**, porque ele roda
`prisma db push` antes de subir e isso removeria a constraint da V2. Use
`npm run db:seed` e `tsx src/index.ts`.

#### Fase 6: login, painel e cookies

Mesmo método, agora com dados reais no banco (cinco agendamentos com status
variados, um deles com dois serviços, e dois bloqueios):

- `POST /auth/login` — corpo idêntico, **e o Java autenticou com o hash bcrypt
  que o seed do Node gravou**, o que prova a compatibilidade do custo 12;
- `GET /admin/health` com e sem cookie, `GET /admin/stats`,
  `GET /admin/appointments` (com e sem `date`), `GET /admin/blocked-slots` —
  idênticos;
- os caminhos de erro (data malformada, intervalo invertido, bloqueio no
  passado, remoção de inexistente) — mesmo status, mesmo `error`, mesma
  `message`;
- os cabeçalhos `Set-Cookie` dos dois cookies, **flag a flag**: `HttpOnly`,
  `Max-Age`, `Path` e `SameSite` batem exatamente.

Restaram **duas diferenças, ambas deliberadas**:

1. **O Java acrescenta `Expires` ao `Set-Cookie`.** O `ResponseCookie` do
   Spring emite `Expires` junto com `Max-Age`; o Node emite só `Max-Age`. Pela
   RFC 6265 o `Max-Age` tem precedência, então o comportamento é o mesmo — e o
   `Expires` ainda ajuda navegadores antigos. Não há como desligá-lo sem
   reimplementar a serialização do cookie à mão.
2. **`error: "Unauthorized"` em vez de `"Error"`** no login recusado. O Node
   faz `error.name || 'Client Error'` e acaba vazando o nome da classe de
   JavaScript. Mesma decisão da Fase 5: o status e a `message` são idênticos, e
   o `error` carrega um código que significa alguma coisa. O frontend nunca o
   exibe — o axios rejeita o 401 antes.

Uma correção saiu dessa comparação: os erros de validação de **parâmetro**
(query e path) passaram a trazer o nome do parâmetro na frente
(`date: date must be YYYY-MM-DD format`), como o `ZodError` monta
`${path}: ${message}`.

### WhatsApp: a porta, o adaptador, e o NoOp

`service/ChatMessagePort` é a interface que o fluxo de agendamento usa — e
**não tem uma palavra sobre Evolution, HTTP ou JSON**. Quem usa define o
contrato; quem implementa se adapta. O ganho é concreto: o teste de
resiliência (abaixo) roda contra Postgres de verdade sem nenhuma linha de rede
no caminho, e trocar a Evolution pela API oficial da Meta um dia não encosta no
serviço de agendamento.

`integration/EvolutionApiClient` fala HTTP. `integration/NoOpChatMessageAdapter`
não faz nada, e assume quando **qualquer uma** das três variáveis falta —
`EVOLUTION_API_URL`, `EVOLUTION_API_KEY`, `EVOLUTION_INSTANCE_NAME`. É o que
permite clonar o repositório e rodar o sistema sem credencial de serviço de
terceiro. A decisão é tomada uma vez no boot, não a cada mensagem.

**Compor o texto é regra de negócio, não transporte.** Fica em
`service/MensagensDeWhatsApp`, código puro: o cliente final lê aquilo, cada
emoji e cada quebra de linha foram escolhidos pelo dono. Se morasse no
adaptador HTTP, mudar a redação exigiria mexer no cliente de rede e testá-la
exigiria subir um servidor.

**Timeouts explícitos:** 3s para conexão, 5s para leitura. O default do
`HttpClient` do JDK é **infinito** para leitura — e como o envio acontece
depois do commit, seriam threads do Tomcat acumulando sem que nenhuma transação
travasse. Sintoma difícil de ler: o banco está bem, o site não responde.

### Resiliência: evento após commit, não try/catch

**O Node:** chama `void whatsappService.sendWelcomeMessage(...)` — promessa
solta, sem `await` — depois de a transação do Prisma ter fechado, com
`try/catch` em volta do `fetch`. Funciona, e tem dois problemas: um `throw`
fora do `try` viraria *unhandled rejection*, e a ordem entre commit e envio é
consequência de como o código foi escrito, não uma regra.

**Aqui** a regra é explícita: `@TransactionalEventListener(AFTER_COMMIT)`. O
ouvinte só é chamado se a transação **comitou**. Isso dá três coisas:

1. **A conexão com o banco já voltou ao pool** antes de qualquer tentativa de
   rede. Com pool de 10, dez clientes agendando durante uma instabilidade da
   Evolution travariam o site inteiro se o envio fosse dentro da transação.
2. **Nunca se manda mensagem de um agendamento que o banco recusou.** O
   perdedor de uma disputa por horário (a constraint de exclusão da V2) não
   recebe "seu agendamento foi confirmado". Há teste para isso.
3. O cliente não espera o WhatsApp para ver a tela de confirmação.

Divergência deliberada. O comportamento observável é o mesmo: a mensagem
chega.

`confirmation_sent` e `confirmation_sent_at` — que sobraram do job de lembrete
desativado — passam a registrar **quem foi avisado**, e portanto quem ficou
sem, para reenvio manual depois de uma instabilidade.

### O reminder NÃO foi portado

O cron de lembrete 1h30 antes (`jobs/reminderJob.ts`) foi desativado em
produção por decisão do cliente, e está comentado no `index.ts` do Node. As
colunas continuam no banco, com o novo uso acima. **O job não existe mais e
não foi recriado.**

### Segurança nos logs

Este projeto já teve um incidente de log vazando conteúdo de conversa de
WhatsApp.

| Nunca | Pode |
| --- | --- |
| o corpo da mensagem | o id do agendamento |
| o telefone completo | sucesso ou falha |
| a resposta bruta da Evolution | o código de status HTTP |
| a `EVOLUTION_API_KEY`, nem truncada | o telefone mascarado: `5522****8402` |

Dois detalhes que não são óbvios e estão no código: o `catch` loga
`ex.getClass().getSimpleName()` e **não** `ex.getMessage()`, porque a mensagem
de uma exceção de HTTP traz a URL e a URL traz o nome da instância; e a
exceção não vai como `cause`, porque o stack trace imprimiria os dois.

`LogsNaoVazamPiiTest` anexa um `ListAppender` do Logback aos loggers das duas
classes, exercita os quatro caminhos que escrevem log (sucesso, recusa, falha
de rede, NoOp) e procura por chave, telefone completo, corpo da mensagem e
resposta bruta — mais uma varredura por qualquer sequência de 11+ dígitos. Uma
regra em comentário não sobrevive ao próximo `log.debug` acrescentado às
pressas.

### CI sem nenhum secret

`.github/workflows/ci.yml` roda em push e pull request: `ubuntu-latest` (tem
Docker, que o Testcontainers precisa), Java 21 Temurin, cache do Maven,
`./mvnw -B -ntp verify`, e o relatório do JaCoCo como artefato.

**O build passa para qualquer pessoa que clonar o repositório, sem configurar
nada.** Num projeto de portfólio, um CI que exige credencial é um CI vermelho
para todo mundo que olha. Isso funciona porque o `EnvironmentValidator` é
`@Profile("!test")` e os testes rodam no perfil `test`; Postgres e Redis sobem
por Testcontainers e não como *services* do Actions; os segredos de teste são
fictícios e vivem em `SegredosDeTeste`; e a Evolution não é configurada, então
o NoOp assume e nenhum teste toca a rede externa.

Verificado localmente com `clean verify` e **todas** as variáveis de ambiente
removidas (`env -u ...`): 487 testes, verde.

### Trabalho futuro

Itens que esta fase deliberadamente **não** resolveu, para não quebrar o
frontend em produção:

1. **Bloqueio sobre horário agendado deveria ser 409** com a lista de clientes
   afetados e confirmação explícita. Exige mudar o painel junto.
2. **`noShowCount` deveria ter recorte de período**, como os outros três
   contadores. Muda um número que o dono vê todo dia.
3. **Coluna de posição em `appointment_services`.** Hoje nem o Node nem o Java
   têm ordem garantida dos serviços de um agendamento; o Java ordena por
   duração decrescente para ser determinístico e, de quebra, bater com a ordem
   de inserção do Node no caso comum ("Corte + Barba"). Uma coluna de posição
   resolveria de verdade — exige migration no schema compartilhado.
4. **Aplicar a V2 em produção**, junto com a remoção do `prisma db push`.
5. **Token CSRF por sessão**, já que `SameSite=None` em produção deixa a defesa
   inteiramente no CORS.
6. **Reenvio manual de WhatsApp** a partir de `confirmation_sent = false`.
   Hoje a coluna registra quem ficou sem aviso, mas não há rota que reenvie.
7. **Retry com backoff no envio.** Hoje uma falha é definitiva até alguém
   reenviar à mão. Uma fila com tentativas espaçadas cobriria a instabilidade
   curta da Evolution, que é o caso comum.

---

## Licença

Projeto privado. Todos os direitos reservados.
