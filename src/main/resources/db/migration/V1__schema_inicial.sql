-- ===========================================================================
-- V1 - schema inicial
--
-- Reproduz o schema que o backend Node (Prisma) mantem em producao. O banco
-- continua sendo lido e escrito pelo Node durante toda a migracao, portanto
-- nomes de tabela, de coluna, de constraint e de indice sao preservados
-- exatamente como estao - nada aqui pode ser "padronizado".
--
-- Sobre a mistura de convencoes: professional_availability usa colunas em
-- camelCase ("dayOfWeek", "startTime", "endTime") porque esses campos nao
-- foram mapeados no schema do Prisma. Todas as outras tabelas usam
-- snake_case. Note o contraste com blocked_slots, que tem start_time e
-- end_time em snake_case. Isso e intencional e nao deve ser corrigido:
-- qualquer rename quebra producao.
--
-- Identificadores entre aspas duplas tambem sao intencionais: sem elas o
-- Postgres rebaixa para minusculas e "dayOfWeek" se tornaria dayofweek.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Tipos enumerados
--
-- A ordem dos valores dentro de cada enum e significativa: define o
-- enumsortorder, usado em comparacao e ORDER BY. NO_SHOW e o ultimo porque
-- foi adicionado depois dos outros quatro.
-- ---------------------------------------------------------------------------
CREATE TYPE "AppointmentStatus" AS ENUM ('PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED', 'NO_SHOW');

CREATE TYPE "Role" AS ENUM ('CLIENT', 'PROFESSIONAL', 'ADMIN');

-- ---------------------------------------------------------------------------
-- users
-- name_encrypted e phone_encrypted guardam PII cifrada com AES-256-CBC.
-- ---------------------------------------------------------------------------
CREATE TABLE "users" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "password_hash" TEXT NOT NULL,
    "name_encrypted" TEXT NOT NULL,
    "phone_encrypted" TEXT NOT NULL,
    "role" "Role" NOT NULL DEFAULT 'CLIENT',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- professionals
-- is_walk_in marca o profissional ficticio usado para atendimento por ordem
-- de chegada, que nao tem agenda propria.
-- ---------------------------------------------------------------------------
CREATE TABLE "professionals" (
    "id" TEXT NOT NULL,
    "name_encrypted" TEXT NOT NULL,
    "bio" TEXT,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "is_walk_in" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "professionals_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- services
-- amount_cents em centavos inteiros: evita erro de ponto flutuante em dinheiro.
-- ---------------------------------------------------------------------------
CREATE TABLE "services" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT,
    "amount_cents" INTEGER NOT NULL,
    "duration_minutes" INTEGER NOT NULL,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "services_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- appointments
-- user_id e nulo em agendamento de visitante (reserva publica, sem conta);
-- nesse caso a PII do visitante fica nas colunas guest_*_encrypted.
-- amount_cents e um snapshot do preco no momento da reserva.
-- ---------------------------------------------------------------------------
CREATE TABLE "appointments" (
    "id" TEXT NOT NULL,
    "user_id" TEXT,
    "professional_id" TEXT NOT NULL,
    "service_id" TEXT NOT NULL,
    "amount_cents" INTEGER NOT NULL,
    "scheduled_at" TIMESTAMP(3) NOT NULL,
    "status" "AppointmentStatus" NOT NULL DEFAULT 'PENDING',
    "notes" TEXT,
    "guest_name_encrypted" TEXT,
    "guest_phone_encrypted" TEXT,
    "guest_email_encrypted" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,
    "cancel_token" TEXT,
    "cancelled_at" TIMESTAMP(3),
    "confirmation_sent" BOOLEAN NOT NULL DEFAULT false,
    "confirmation_sent_at" TIMESTAMP(3),

    CONSTRAINT "appointments_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- appointment_services
-- Um agendamento pode combinar varios servicos. Guarda snapshot de preco e
-- duracao por item, porque o catalogo muda com o tempo.
-- ---------------------------------------------------------------------------
CREATE TABLE "appointment_services" (
    "id" TEXT NOT NULL,
    "appointment_id" TEXT NOT NULL,
    "service_id" TEXT NOT NULL,
    "amount_cents" INTEGER NOT NULL,
    "duration_minutes" INTEGER NOT NULL,

    CONSTRAINT "appointment_services_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- professional_availability
-- Jornada semanal recorrente. "dayOfWeek": 0 = domingo ... 6 = sabado.
-- "startTime"/"endTime" sao TEXT no formato "HH:MM".
-- ATENCAO: colunas em camelCase, diferente do resto do banco. Nao renomear.
-- ---------------------------------------------------------------------------
CREATE TABLE "professional_availability" (
    "id" TEXT NOT NULL,
    "professional_id" TEXT NOT NULL,
    "dayOfWeek" INTEGER NOT NULL,
    "startTime" TEXT NOT NULL,
    "endTime" TEXT NOT NULL,

    CONSTRAINT "professional_availability_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- refresh_tokens
-- Guarda apenas o hash do token, nunca o token em si.
-- ---------------------------------------------------------------------------
CREATE TABLE "refresh_tokens" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "token_hash" TEXT NOT NULL,
    "expires_at" TIMESTAMP(3) NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "refresh_tokens_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- push_subscriptions
-- Inscricoes Web Push (endpoint + chaves p256dh/auth do navegador).
-- ---------------------------------------------------------------------------
CREATE TABLE "push_subscriptions" (
    "id" TEXT NOT NULL,
    "endpoint" TEXT NOT NULL,
    "p256dh" TEXT NOT NULL,
    "auth" TEXT NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "push_subscriptions_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- blocked_slots
-- Bloqueio pontual de agenda (folga, almoco, imprevisto). "date" e TEXT
-- "YYYY-MM-DD"; start_time/end_time sao TEXT "HH:MM" - aqui em snake_case.
-- ---------------------------------------------------------------------------
CREATE TABLE "blocked_slots" (
    "id" TEXT NOT NULL,
    "professional_id" TEXT NOT NULL,
    "date" TEXT NOT NULL,
    "start_time" TEXT NOT NULL,
    "end_time" TEXT NOT NULL,
    "reason" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "blocked_slots_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- Indices unicos
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX "users_email_key" ON "users"("email");

CREATE UNIQUE INDEX "appointments_cancel_token_key" ON "appointments"("cancel_token");

CREATE UNIQUE INDEX "professional_availability_professional_id_dayOfWeek_key" ON "professional_availability"("professional_id", "dayOfWeek");

CREATE UNIQUE INDEX "refresh_tokens_token_hash_key" ON "refresh_tokens"("token_hash");

CREATE UNIQUE INDEX "push_subscriptions_endpoint_key" ON "push_subscriptions"("endpoint");

-- ---------------------------------------------------------------------------
-- Chaves estrangeiras
--
-- SET NULL em appointments.user_id: apagar a conta preserva o historico do
-- agendamento. CASCADE em refresh_tokens e appointment_services: sao dados
-- que nao fazem sentido sem o pai. RESTRICT no resto.
-- ---------------------------------------------------------------------------
ALTER TABLE "appointments" ADD CONSTRAINT "appointments_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "appointments" ADD CONSTRAINT "appointments_professional_id_fkey" FOREIGN KEY ("professional_id") REFERENCES "professionals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "appointments" ADD CONSTRAINT "appointments_service_id_fkey" FOREIGN KEY ("service_id") REFERENCES "services"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "appointment_services" ADD CONSTRAINT "appointment_services_appointment_id_fkey" FOREIGN KEY ("appointment_id") REFERENCES "appointments"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "appointment_services" ADD CONSTRAINT "appointment_services_service_id_fkey" FOREIGN KEY ("service_id") REFERENCES "services"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "professional_availability" ADD CONSTRAINT "professional_availability_professional_id_fkey" FOREIGN KEY ("professional_id") REFERENCES "professionals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "refresh_tokens" ADD CONSTRAINT "refresh_tokens_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "blocked_slots" ADD CONSTRAINT "blocked_slots_professional_id_fkey" FOREIGN KEY ("professional_id") REFERENCES "professionals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
