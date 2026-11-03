-- ===========================================================================
-- V3 - completed_at: o fim REAL do atendimento
--
-- O PEDIDO
--
-- O Jhonatan quer que terminar um corte de 50 minutos em 30 devolva os outros
-- 20 para a agenda. Nao e o que acontecia: COMPLETED simplesmente deixava de
-- ocupar, e o vao seguinte continuava comecando no fim PREVISTO, porque era
-- scheduled_at + slot_duration_minutes que formava o bloco. Os minutos
-- liberados nao viravam horario oferecivel -- apenas desapareciam.
--
-- Com esta coluna, um agendamento concluido ocupa o intervalo
-- scheduled_at -> completed_at, e o vao seguinte comeca onde o atendimento
-- de fato acabou.
--
-- NULLABLE, E ISSO IMPORTA
--
-- Toda linha COMPLETED que ja existe nasce com completed_at nulo, e o
-- calculo trata COMPLETED sem completed_at como NAO ocupando -- o
-- comportamento de antes desta migration. Sem essa regra, um
-- backfill implicito faria agendamentos antigos passarem a bloquear a agenda
-- de dias passados, mudando numeros que o dono ja viu.
--
-- Espelha o padrao de cancelled_at, que a V1 criou com o mesmo tipo e a mesma
-- ausencia de default: a coluna de carimbo de evento so existe depois do
-- evento.
--
-- ATENCAO, CORTE: ISTO O `prisma db push` DESTROI
--
-- O schema.prisma do backend Node nao conhece esta coluna, e o `start` dele e
-- `prisma db push && node dist/index.js`. Todo deploy do Node, portanto,
-- APAGA completed_at -- junto com a constraint de exclusao da V2, pelo mesmo
-- mecanismo e em silencio. As duas perdas sao verificadas no boot pelo
-- VerificaAConstraintDeSobreposicao.
--
-- Consequencia pratica: esta migration e a V2 so podem conviver com o Node em
-- producao enquanto o `db push` estiver fora do script de start dele. Tirar o
-- `db push` e pre-requisito do corte, nao detalhe de arrumacao.
-- ===========================================================================

ALTER TABLE "appointments"
    ADD COLUMN IF NOT EXISTS "completed_at" TIMESTAMP(3);

COMMENT ON COLUMN "appointments"."completed_at" IS
    'Fim real do atendimento, carimbado quando o status vira COMPLETED. Nulo em '
    'agendamento nao concluido e nas linhas anteriores a V3. Um COMPLETED com '
    'completed_at nulo NAO ocupa agenda; com valor, ocupa de scheduled_at ate '
    'aqui.';
