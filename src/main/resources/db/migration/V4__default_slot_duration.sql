-- ===========================================================================
-- V4 - DEFAULT 50 em slot_duration_minutes: a V2 passa a admitir o INSERT do
-- Node
--
-- O PROBLEMA QUE ESTA MIGRATION RESOLVE
--
-- A V2 criou "appointments"."slot_duration_minutes" NOT NULL e SEM default.
-- O Prisma do backend Node nao conhece essa coluna: o schema.prisma dele nao
-- a declara, entao o INSERT que ele monta nao a menciona. Sem default, o
-- Postgres recusa a linha com
--
--     null value in column "slot_duration_minutes" violates not-null
--     constraint
--
-- e TODO agendamento criado pelo Node falha. Nao e degradacao, e
-- indisponibilidade total de agendamento -- a partir do minuto em que a V2 e
-- aplicada em producao, e nao a partir do corte.
--
-- A janela existe porque as migrations entram ANTES da troca do dominio para
-- o Java. Nesse intervalo quem atende cliente ainda e o Node, escrevendo no
-- mesmo banco que o Flyway acabou de alterar. O default e o que torna esse
-- intervalo sobrevivivel.
--
-- POR QUE 50, E NAO 30
--
-- 30 e o fallback de LEITURA (SlotQueryMapper.DURACAO_PADRAO_MINUTOS), usado
-- quando um agendamento nao tem item nenhum. Seria a escolha natural se esta
-- coluna fosse lida. Ela nao e: nenhum caminho de leitura do Java consulta
-- slot_duration_minutes. O SlotQueryMapper RECALCULA a duracao a partir de
-- appointment_services, com teto de 50, toda vez. A coluna existe para uma
-- unica finalidade -- alimentar a expressao do indice da constraint de
-- exclusao da V2:
--
--     tsrange(scheduled_at, scheduled_at + make_interval(mins => ...))
--
-- Entao a pergunta nao e "qual valor descreve melhor o atendimento", e sim
-- "qual valor erra para o lado certo na constraint". 50 e o teto da regra de
-- negocio (MAX_SLOT_MINUTES), e um intervalo maior torna a constraint MAIS
-- conservadora: ela recusa mais, nunca menos. Como a funcao dela e impedir
-- duplo agendamento, errar reservando demais preserva a garantia; errar
-- reservando de menos a perde.
--
-- O QUE ISSO CUSTA, PARA NAO SER DESCOBERTO DEPOIS
--
-- Enquanto conviverem linhas escritas pelo Node com duracao real menor que
-- 50, a grade e a constraint discordam por desenho. Um agendamento de um
-- servico de 30 minutos as 10:00 criado pelo Node:
--
--   * a grade do Java recalcula 30 e oferece as 10:30;
--   * a constraint enxerga 10:00 -> 10:50 e RECUSA uma reserva as 10:30.
--
-- Ou seja, o cliente pode receber 409 num horario que a tela ofereceu. E o
-- preco explicito desta escolha, e e o lado barato da troca: a alternativa e
-- a constraint reservar DE MENOS e deixar passar uma sobreposicao real, que
-- e dois clientes na porta no mesmo minuto.
--
-- O alcance e limitado e se esgota sozinho. As linhas anteriores a V2 nao
-- entram nisso -- o backfill da propria V2 gravou nelas a duracao calculada,
-- nao o default. Afetadas sao so as criadas pelo Node DENTRO da janela de
-- convivencia, e so enquanto o horario delas nao passar. Encurtar a janela
-- encurta o problema.
--
-- ESTE DEFAULT E TEMPORARIO -- REMOVA-O
--
-- Assim que o Node sair do ar, uma migration posterior deve fazer
--
--     ALTER TABLE "appointments" ALTER COLUMN "slot_duration_minutes"
--         DROP DEFAULT;
--
-- O Java SEMPRE informa o valor explicitamente, via
-- SlotQueryMapper.slotDurationMinutes, entao o default nao e usado por ele em
-- nenhum caminho. Deixa-lo para tras troca uma falha alta por uma silenciosa:
-- um bug futuro de escrita que esquecesse de preencher a coluna gravaria 50 e
-- seguiria adiante, desalinhando o indice do que a tela mostra, em vez de
-- estourar no INSERT. Um NOT NULL honesto e melhor que um default que
-- esconde.
--
-- NAO EDITE A V2 PARA FAZER ISTO
--
-- E o motivo de esta migration existir em vez de um ajuste de uma linha na
-- V2: a V2 ja foi aplicada em bancos locais e no CI, e o Flyway guarda o
-- checksum do arquivo. Mudar o texto dela faria todo boot com historico
-- existente falhar em "Migration checksum mismatch", inclusive em producao
-- depois do corte.
-- ===========================================================================

ALTER TABLE "appointments"
    ALTER COLUMN "slot_duration_minutes" SET DEFAULT 50;

COMMENT ON COLUMN "appointments"."slot_duration_minutes" IS
    'Minutos que o agendamento ocupa na agenda, com teto de 50. Alimenta a '
    'constraint de exclusao da V2 e nada mais: a leitura recalcula a duracao a '
    'partir de appointment_services. O DEFAULT 50 da V4 e TEMPORARIO, existe '
    'para o INSERT do Prisma durante a convivencia com o backend Node, e deve '
    'ser removido quando o Node sair do ar.';
