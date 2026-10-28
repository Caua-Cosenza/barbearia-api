package com.cauacosenza.barbearia.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/admin/push-subscription}.
 *
 * <p>O formato e o do objeto {@code PushSubscription} do navegador, como
 * {@code subscription.toJSON()} o entrega — dai as chaves ficarem aninhadas em
 * {@code keys} em vez de planas. O frontend repassa o objeto quase intacto.
 *
 * @param endpoint a URL que o servico de push do navegador devolveu. Tem
 *     indice unico: reinscrever o mesmo dispositivo atualiza a linha em vez de
 *     duplicar
 * @param keys as chaves publicas usadas para cifrar o payload da notificacao
 */
public record PushSubscriptionRequest(

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Size(min = 1, message = "Invalid url")
		String endpoint,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Valid
		Keys keys) {

	public record Keys(

			@NotNull(message = ValidationPatterns.OBRIGATORIO)
			@Size(min = 1, message = "String must contain at least 1 character(s)")
			String p256dh,

			@NotNull(message = ValidationPatterns.OBRIGATORIO)
			@Size(min = 1, message = "String must contain at least 1 character(s)")
			String auth) {

	}

}
