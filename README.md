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
└── integration/                testes de ponta a ponta com Testcontainers
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

*A ser preenchido nas próximas fases.*

---

## Licença

Projeto privado. Todos os direitos reservados.
