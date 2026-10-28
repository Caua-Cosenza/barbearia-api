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

}
