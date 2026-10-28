package com.cauacosenza.barbearia.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/auth/login}.
 *
 * <p>Traducao do {@code LoginSchema} (Zod) do Node: e-mail valido e senha com
 * pelo menos um caractere.
 *
 * <p><b>Nao ha regra de forca de senha aqui</b>, e isso e correto: no login se
 * verifica a senha que ja existe, nao se cria uma. Exigir formato no login
 * contaria ao atacante que a senha de alguem nao segue a regra atual, e
 * recusaria quem tem uma senha antiga antes mesmo de comparar.
 */
public record LoginRequest(

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Email(message = "Invalid email")
		String email,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Size(min = 1, message = "String must contain at least 1 character(s)")
		String password) {

}
