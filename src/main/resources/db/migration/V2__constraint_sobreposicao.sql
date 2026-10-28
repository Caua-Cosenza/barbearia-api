-- ===========================================================================
-- V2 - constraint de exclusao contra duplo agendamento
--
-- O PROBLEMA
--
-- Verificar conflito antes de inserir deixa uma janela entre a checagem e o
-- insert. Duas requisicoes simultaneas para o mesmo horario leem a agenda
-- vazia, as duas concluem que esta livre, e as duas gravam. Nao e teoria: e o
-- que o backend Node faz hoje, e o cliente descobre no balcao.
--
-- Fechar a janela no codigo exigiria lock pessimista por profissional em toda
-- reserva, serializando a operacao mais frequente do sistema. O Postgres
-- resolve isso na definicao do schema: uma constraint de exclusao e verificada
-- dentro do commit, com indice, e nao ha janela nenhuma para fechar.
--
-- A COLUNA NOVA
--
-- Uma constraint de exclusao precisa de uma expressao IMUTAVEL sobre a propria
-- linha. A duracao do atendimento mora em appointment_services, em outra
-- tabela - uma soma com subconsulta nao e imutavel e nao pode entrar no
-- indice. Por isso a duracao efetiva e materializada em
-- appointments.slot_duration_minutes.
--
-- A regra, identica a que SlotQueryMapper.slotDurationMinutes aplica na
-- leitura: soma das duracoes dos itens, com TETO DE 50 minutos, e FALLBACK DE
-- 30 quando o agendamento nao tem item nenhum. O teto e regra de negocio
-- (corte de 50 + pigmentacao de 10 ocupam 50, nao 60) e vale na escrita e na
-- leitura - as duas pontas tem que concordar, ou a agenda mostra um horario
-- que a constraint vai recusar.
--
-- ATENCAO - ESTA MIGRATION NAO SERA APLICADA EM PRODUCAO NESTA FASE
--
-- Dois motivos, nenhum deles tecnico-opcional:
--
--   1. Ela altera um banco que o backend Node usa agora. A coluna
--      slot_duration_minutes entra NOT NULL sem default, e todo INSERT do
--      Prisma passaria a falhar - o Prisma nao conhece a coluna.
--   2. O start script do Node roda "prisma db push", que sincroniza o banco
--      com o schema.prisma. Como o Prisma nao conhece nem a coluna nem a
--      constraint, o proximo deploy do Node as removeria em silencio.
--
-- Aplicar em producao e parte do plano de corte, junto com a remocao daquele
-- db push. Ate la esta V2 vive no ambiente de teste, onde e o que prova que a
-- garantia funciona. Ver README, secao "Decisoes tecnicas".
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- btree_gist: o GiST nao sabe comparar text com "=" por conta propria. A
-- extensao fornece as classes de operador de igualdade que o indice precisa
-- para combinar professional_id (igualdade) com o intervalo (sobreposicao) na
-- mesma constraint.
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- Entra nullable para poder fazer o backfill antes de exigir NOT NULL.
-- ---------------------------------------------------------------------------
ALTER TABLE "appointments"
    ADD COLUMN "slot_duration_minutes" INTEGER;

-- ---------------------------------------------------------------------------
-- Backfill.
--
-- O HAVING COUNT(*) > 0 nao e redundancia: sem ele, a subconsulta agregada
-- devolve UMA linha com SUM = NULL quando o agendamento nao tem item algum.
-- E LEAST(NULL, 50) em Postgres nao e NULL - LEAST e GREATEST IGNORAM
-- argumentos nulos - entao seria 50, e o fallback de 30 nunca aconteceria. O
-- HAVING suprime a linha agregada, a subconsulta devolve NULL de verdade, e o
-- COALESCE faz o seu trabalho.
-- ---------------------------------------------------------------------------
UPDATE "appointments" a
SET "slot_duration_minutes" = COALESCE(
        (SELECT LEAST(SUM(s."duration_minutes"), 50)
         FROM "appointment_services" s
         WHERE s."appointment_id" = a."id"
         HAVING COUNT(*) > 0),
        30);

ALTER TABLE "appointments"
    ALTER COLUMN "slot_duration_minutes" SET NOT NULL;

-- ---------------------------------------------------------------------------
-- A constraint.
--
-- tsrange e nao tstzrange: scheduled_at e TIMESTAMP(3) SEM fuso. Passar um
-- timestamp sem fuso para tstzrange() forcaria uma conversao usando o
-- TimeZone da sessao, que depende do cliente - nao e imutavel e o Postgres
-- recusaria a expressao no indice.
--
-- make_interval(mins => ...) e nao (coluna || ' minutes')::interval: o cast de
-- texto para interval depende de IntervalStyle, e make_interval e imutavel por
-- construcao. Mesmo resultado, sem depender de GUC.
--
-- O range e [inicio, fim) - fechado no inicio, aberto no fim, que e o default
-- de tsrange. Dai que agendamentos encostados nao conflitam: um que termina as
-- 10:00 e outro que comeca as 10:00 compartilham apenas o ponto excluido.
--
-- O WHERE parcial e o que faz cancelado nao ocupar agenda, exatamente como o
-- filtro de AppointmentStatus.ocupaAgenda() nas consultas. Cancelar e
-- reagendar no mesmo horario funciona; dois PENDING no mesmo horario, nao.
-- ---------------------------------------------------------------------------
ALTER TABLE "appointments"
    ADD CONSTRAINT "appointments_sem_sobreposicao"
    EXCLUDE USING gist (
        "professional_id" WITH =,
        tsrange("scheduled_at",
                "scheduled_at" + make_interval(mins => "slot_duration_minutes")) WITH &&
    ) WHERE ("status" IN ('PENDING', 'CONFIRMED'));
