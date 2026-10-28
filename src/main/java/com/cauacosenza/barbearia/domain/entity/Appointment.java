package com.cauacosenza.barbearia.domain.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.security.EncryptedStringConverter;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Uma reserva — tabela {@code appointments}.
 *
 * <p><b>Reserva publica vs. cliente logado.</b> {@code user} e nulo na reserva
 * publica, e nesse caso a PII vai em {@code guestName}, {@code guestPhone} e
 * {@code guestEmail}, cifradas. Com cliente logado o caminho e o inverso: a
 * PII mora em {@link AdminUser} e as colunas de visitante ficam nulas. A FK
 * usa {@code ON DELETE SET NULL} justamente para que apagar a conta nao apague
 * o historico do agendamento.
 *
 * <p><b>{@code scheduledAt} e {@code Instant}.</b> A coluna e
 * {@code TIMESTAMP(3)} <i>sem</i> fuso, e o conteudo e UTC — convencao do
 * Node, que grava {@code Date} do JavaScript. O
 * {@code hibernate.jdbc.time_zone=UTC} do {@code application.yml} e o
 * {@code @JdbcTypeCode(TIMESTAMP)} daqui fazem a leitura e a escrita tratarem
 * a coluna como UTC independentemente do fuso da JVM e do servidor. Nunca ha
 * {@code LocalDateTime} nesta classe: converter para hora de Brasilia e
 * trabalho do {@code SlotQueryMapper}.
 *
 * <p><b>{@code amountCents} e snapshot.</b> O preco do momento da reserva, nao
 * o preco atual do {@link Service}. Reajustar o catalogo nao reescreve a
 * historia.
 *
 * <p><b>{@code slotDurationMinutes} e a coluna que a V2 acrescentou.</b> E a
 * duracao efetiva do atendimento — soma das duracoes de {@link BookedService},
 * com teto de 50 e fallback de 30 quando nao ha nenhum — materializada na
 * linha porque a constraint de exclusao precisa dela para montar o intervalo
 * ocupado. Nao da para derivar em tempo de consulta: uma constraint de
 * exclusao exige expressao imutavel, e uma soma em outra tabela nao e. Quem
 * grava um agendamento preenche este campo de forma coerente com os itens de
 * {@code bookedServices}, e {@code SlotQueryMapper.slotDurationMinutes} e a
 * definicao unica desse calculo.
 */
@Entity
@Table(name = "appointments")
@Getter
@Setter
@NoArgsConstructor
public class Appointment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	/** Nulo em reserva publica. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private AdminUser user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "professional_id", nullable = false)
	private Barber professional;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false)
	private Service service;

	@Column(name = "amount_cents", nullable = false)
	private int amountCents;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "scheduled_at", nullable = false)
	private Instant scheduledAt;

	/**
	 * Tipo enumerado nativo {@code "AppointmentStatus"}, nao varchar.
	 *
	 * <p>{@code NAMED_ENUM} faz o Hibernate enviar o valor como tipo
	 * desconhecido, que o Postgres resolve para o enum da coluna. Sem isso o
	 * driver manda varchar e o banco responde que a coluna e do tipo
	 * AppointmentStatus mas a expressao e character varying.
	 */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false)
	private AppointmentStatus status = AppointmentStatus.PENDING;

	@Column(name = "notes")
	private String notes;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "guest_name_encrypted")
	private String guestName;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "guest_phone_encrypted")
	private String guestPhone;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "guest_email_encrypted")
	private String guestEmail;

	/** Token do link de cancelamento enviado ao cliente. Indice unico. */
	@Column(name = "cancel_token")
	private String cancelToken;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "confirmation_sent", nullable = false)
	private boolean confirmationSent = false;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "confirmation_sent_at")
	private Instant confirmationSentAt;

	/** Acrescentada pela V2. Ver o javadoc da classe. */
	@Column(name = "slot_duration_minutes", nullable = false)
	private int slotDurationMinutes;

	/**
	 * Os itens do agendamento. {@code orphanRemoval} acompanha o
	 * {@code ON DELETE CASCADE} da FK: um item nao existe sem o agendamento.
	 */
	@OneToMany(mappedBy = "appointment", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<BookedService> bookedServices = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Mantem as duas pontas da associacao coerentes em memoria. */
	public void adicionarServico(BookedService item) {
		item.setAppointment(this);
		this.bookedServices.add(item);
	}

	@PrePersist
	void aoInserir() {
		Instant agora = Instant.now();
		if (this.createdAt == null) {
			this.createdAt = agora;
		}
		this.updatedAt = agora;
	}

	@PreUpdate
	void aoAtualizar() {
		this.updatedAt = Instant.now();
	}

}
