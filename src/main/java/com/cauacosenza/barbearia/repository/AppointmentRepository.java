package com.cauacosenza.barbearia.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;

/**
 * Consultas sobre {@code appointments}.
 */
public interface AppointmentRepository extends JpaRepository<Appointment, String> {

	/**
	 * Os status que bloqueiam horario. Mesma regra de
	 * {@link AppointmentStatus#ocupaAgenda()} e mesmo conjunto do {@code WHERE}
	 * da constraint de exclusao da V2.
	 */
	Set<AppointmentStatus> OCUPAM_AGENDA = Set.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED);

	/**
	 * Os agendamentos de um profissional que <b>ocupam agenda</b> dentro de um
	 * intervalo. E a consulta que alimenta o calculo de horarios livres.
	 *
	 * <p><b>O filtro de status nao e opcional para quem chama.</b> Somente
	 * {@code PENDING} e {@code CONFIRMED} bloqueiam horario; cancelado,
	 * concluido e no-show nao. O ponto de entrada publico e
	 * {@link #buscarQueOcupamAgenda(String, Instant, Instant)}, que injeta
	 * {@link #OCUPAM_AGENDA} — deixar a escolha para o chamador seria convidar
	 * o bug em que um dia inteiro parece cheio de atendimentos que ja
	 * terminaram.
	 *
	 * <p><b>Por que os status vem por parametro e nao como literal na JPQL.</b>
	 * Escrever {@code a.status in (AppointmentStatus.PENDING, ...)} parece mais
	 * direto e <i>nao funciona neste banco</i>: o Hibernate inlina o literal
	 * como {@code 'PENDING'::AppointmentStatus}, sem aspas no nome do tipo, e o
	 * Postgres rebaixa para {@code appointmentstatus}. O tipo enumerado foi
	 * criado pelo Prisma como {@code "AppointmentStatus"}, em camelCase, entao
	 * o cast nao encontra tipo nenhum e a consulta falha com <i>type
	 * "appointmentstatus" does not exist</i>. Por parametro o valor passa pelo
	 * binder de {@code NAMED_ENUM}, que envia como tipo desconhecido e deixa o
	 * Postgres resolver pela coluna — sem citar o nome do tipo em lugar algum.
	 *
	 * <p>O {@code join fetch} nos itens evita N+1: o mapper soma a duracao de
	 * cada {@code BookedService} para montar o bloco ocupado, e sem o fetch
	 * cada agendamento viraria um SELECT extra. {@code distinct} porque o join
	 * multiplica a linha do agendamento por item.
	 */
	@Query("""
			select distinct a from Appointment a
			left join fetch a.bookedServices
			where a.professional.id = :professionalId
			  and a.status in :statuses
			  and a.scheduledAt >= :inicio
			  and a.scheduledAt < :fim
			order by a.scheduledAt
			""")
	List<Appointment> buscarPorStatus(
			@Param("professionalId") String professionalId,
			@Param("inicio") Instant inicio,
			@Param("fim") Instant fim,
			@Param("statuses") Collection<AppointmentStatus> statuses);

	/**
	 * Ver {@link #buscarPorStatus}.
	 *
	 * @param professionalId profissional
	 * @param inicio limite inferior, <b>inclusivo</b>
	 * @param fim limite superior, <b>exclusivo</b> — para um dia inteiro passe
	 *     a meia-noite do dia seguinte, nao 23:59:59
	 */
	default List<Appointment> buscarQueOcupamAgenda(String professionalId, Instant inicio, Instant fim) {
		return buscarPorStatus(professionalId, inicio, fim, OCUPAM_AGENDA);
	}

	/**
	 * Localiza o agendamento pelo token do link de cancelamento.
	 *
	 * <p>Sem filtro de status de proposito: o cliente que clica duas vezes no
	 * link precisa receber "ja cancelado" e nao "nao encontrado".
	 *
	 * <p>A coluna tem indice unico, por isso {@link Optional} e nao lista. Note
	 * que ela e NULLABLE: no Postgres um indice unico admite varios nulos, e
	 * agendamento sem token nunca sera encontrado por aqui — o que e o
	 * comportamento desejado, mas significa que o chamador precisa tratar o
	 * vazio.
	 */
	Optional<Appointment> findByCancelToken(String cancelToken);

	/**
	 * Todos os agendamentos de um intervalo, <b>sem filtrar status</b>, para a
	 * tabela do painel.
	 *
	 * <p>O contraste com {@link #buscarQueOcupamAgenda} e deliberado: o
	 * calculo de horarios so quer o que ocupa agenda, mas o painel precisa
	 * mostrar tambem o que foi cancelado e o no-show — e justamente o historico
	 * do dia que o dono quer ver.
	 *
	 * <p>O {@code join fetch} nas quatro associacoes evita N+1 e, mais do que
	 * isso, evita {@code LazyInitializationException}: a resposta e montada
	 * fora da transacao e precisa do nome do servico, do profissional e da PII
	 * da conta. {@code bookedServices} e a unica colecao no fetch — duas
	 * colecoes num mesmo {@code join fetch} fariam o Hibernate recusar a
	 * consulta ("cannot simultaneously fetch multiple bags").
	 *
	 * <p>{@code user} entra com {@code left join} porque e nulo em reserva
	 * publica, que e o caso comum.
	 */
	@Query("""
			select distinct a from Appointment a
			join fetch a.professional
			join fetch a.service
			left join fetch a.user
			left join fetch a.bookedServices item
			left join fetch item.service
			where a.scheduledAt >= :inicio
			  and a.scheduledAt <= :fim
			order by a.scheduledAt
			""")
	List<Appointment> buscarDoPeriodoParaOPainel(
			@Param("inicio") Instant inicio,
			@Param("fim") Instant fim);

	/**
	 * Quantos agendamentos de um intervalo estao em algum dos status dados.
	 *
	 * <p>Os limites sao <b>os dois inclusivos</b>, ao contrario de
	 * {@link #buscarQueOcupamAgenda}. Nao e descuido: o Node monta o fim do dia
	 * como {@code 23:59:59.999} e compara com {@code lte}, e os contadores do
	 * painel tem que bater numero a numero com os dele.
	 */
	long countByScheduledAtBetweenAndStatusIn(
			Instant inicio, Instant fim, Collection<AppointmentStatus> statuses);

	/**
	 * Quantos no-shows existem <b>no banco inteiro</b>, sem recorte de
	 * periodo.
	 *
	 * <p>E o comportamento do Node
	 * ({@code prisma.appointment.count({ where: { status: 'NO_SHOW' } })}) e
	 * esta reproduzido de proposito, apesar de os outros tres contadores do
	 * painel serem por periodo. Divergencia conhecida, registrada no README:
	 * corrigi-la mudaria um numero que o dono ve todo dia, e isso e decisao
	 * dele, nao desta migracao.
	 */
	long countByStatus(AppointmentStatus status);

}
