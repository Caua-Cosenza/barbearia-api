package com.cauacosenza.barbearia.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Exige que a data e hora ja validadas estejam no futuro, como o
 * {@code .refine(v => new Date(v) > new Date())} do Zod.
 *
 * <p>Esta anotacao e o motivo de agendamento no passado responder <b>422</b> e
 * nao 400: a regra vive na validacao de entrada, que roda antes do service, e
 * por isso a {@code AgendamentoNoPassadoException} do dominio — que daria 400
 * — nunca e alcancada por HTTP. E a mesma assimetria do Node, onde o
 * {@code refine} do Zod sombreia o {@code if} do
 * {@code appointmentService.createPublic}.
 *
 * <p>Valor malformado e considerado valido aqui, deixando a recusa para
 * {@link DataHoraComFuso}: duas anotacoes reclamando do mesmo caractere errado
 * produziriam duas mensagens para uma falha so.
 */
@Documented
@Constraint(validatedBy = DataFutura.Validador.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface DataFutura {

	String message() default "Data deve ser no futuro";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/**
	 * <p>O {@link Clock} e injetado, e nao {@code Instant.now()} direto: tem
	 * que ser <b>o mesmo relogio</b> que o {@code PublicAppointmentService}
	 * usa. Com relogios diferentes as duas camadas discordam sobre o que e
	 * "passado", e o pedido que a validacao aprova o service recusa — a mesma
	 * entrada sai como 422 ou como 400 dependendo de qual deles olhou
	 * primeiro. O Spring sabe injetar em validadores porque registra o
	 * {@code SpringConstraintValidatorFactory}.
	 */
	class Validador implements ConstraintValidator<DataFutura, String> {

		private final Clock clock;

		public Validador(Clock clock) {
			this.clock = clock;
		}

		@Override
		public boolean isValid(String valor, ConstraintValidatorContext contexto) {
			if (valor == null || valor.isBlank()) {
				return true;
			}
			try {
				// Estritamente maior que agora, como o '>' do Node: agendar
				// para o instante exato em que o pedido chega nao faz sentido.
				return OffsetDateTime.parse(valor).toInstant().isAfter(Instant.now(this.clock));
			}
			catch (DateTimeParseException ex) {
				return true;
			}
		}

	}

}
