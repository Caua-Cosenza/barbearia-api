package com.cauacosenza.barbearia.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.EnumSet;
import java.util.Set;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;

/**
 * Restringe a mudanca de status do painel aos quatro valores que o
 * {@code UpdateStatusSchema} do Node aceita.
 *
 * <p>{@code PENDING} fica de fora: o admin nao devolve um agendamento ao
 * estado inicial. Um atendimento ja concluido que voltasse a PENDING
 * reocuparia a agenda — o {@code WHERE} parcial da constraint de exclusao
 * conta {@code PENDING} como ocupado — e reapareceria como pendente no painel.
 *
 * <p>A lista vive aqui, e nao num {@code if} do service, para que um valor
 * invalido vire <b>422 no envelope</b> com o nome do campo, como no Node, em
 * vez de erro de desserializacao ou de regra de negocio.
 */
@Documented
@Constraint(validatedBy = StatusPermitidoParaAdmin.Validador.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface StatusPermitidoParaAdmin {

	String message() default
			"Invalid enum value. Expected 'CONFIRMED' | 'CANCELLED' | 'COMPLETED' | 'NO_SHOW'";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validador implements ConstraintValidator<StatusPermitidoParaAdmin, AppointmentStatus> {

		private static final Set<AppointmentStatus> PERMITIDOS = EnumSet.of(
				AppointmentStatus.CONFIRMED,
				AppointmentStatus.CANCELLED,
				AppointmentStatus.COMPLETED,
				AppointmentStatus.NO_SHOW);

		@Override
		public boolean isValid(AppointmentStatus valor, ConstraintValidatorContext contexto) {
			// Nulo e valido aqui: quem exige presenca e o @NotNull, e duas
			// anotacoes reclamando da mesma ausencia dariam duas mensagens.
			return valor == null || PERMITIDOS.contains(valor);
		}

	}

}
