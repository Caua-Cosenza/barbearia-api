# Barbearia API

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
├── config/                     security, CORS, Redis, rate limit, validação de ambiente
└── exception/                  tratamento global de erro → envelope de erro

src/main/resources/
├── application.yml             configuração dos três perfis
└── db/migration/               migrations Flyway (V1__schema_inicial.sql ...)

src/test/java/com/cauacosenza/barbearia/
├── domain/rules/               testes unitários das regras puras (sem Spring)
├── security/                   testes do CryptoService (sem Spring)
├── service/                    testes do mapper (sem Spring, sem banco)
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

---

## Licença

Projeto privado. Todos os direitos reservados.
