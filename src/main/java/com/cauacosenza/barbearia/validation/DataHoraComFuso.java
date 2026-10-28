package com.cauacosenza.barbearia.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Exige ISO-8601 <b>com fuso explicito</b>, como o
 * {@code z.string().datetime({ offset: true })} do Zod.
 *
 * <p>A exigencia do fuso nao e preciosismo: {@code "2026-10-28T10:00:00"} sem
 * sufixo e ambiguo, e interpreta-lo como UTC poria o atendimento tres horas
 * adiante. Aceita-se {@code Z} ou {@code ±HH:MM}, que e o que o
 * {@link OffsetDateTime#parse(CharSequence)} entende. O frontend monta
 * {@code `${date}T${timeSlot}:00-03:00`}, sempre com o offset.
 *
 * <p><b>Nota de arqueologia.</b> Existe no frontend uma tela
 * {@code pages/Booking.tsx} que monta {@code `${date}T${timeSlot}:00`}, sem
 * offset — e que portanto seria recusada aqui, como ja e recusada pelo Zod. A
 * tela nao esta roteada em {@code App.tsx}: e codigo morto. O fluxo vivo e
 * {@code Home} → {@code BookingModal}, que manda o offset.
 *
 * <p>Nulo e considerado valido: quem exige presenca e o {@code @NotBlank}.
 * Misturar as duas responsabilidades produziria duas mensagens para a mesma
 * falha.
 */
@Documented
@Constraint(validatedBy = DataHoraComFuso.Validador.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface DataHoraComFuso {

	String message() default "Invalid datetime format";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validador implements ConstraintValidator<DataHoraComFuso, String> {

		@Override
		public boolean isValid(String valor, ConstraintValidatorContext contexto) {
			if (valor == null || valor.isBlank()) {
				return true;
			}
			try {
				OffsetDateTime.parse(valor);
				return true;
			}
			catch (DateTimeParseException ex) {
				return false;
			}
		}

	}

}
