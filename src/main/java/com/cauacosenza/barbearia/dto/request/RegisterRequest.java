package com.cauacosenza.barbearia.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/auth/register}.
 *
 * <p><b>Nenhuma tela chama esta rota.</b> Ela existe no
 * {@code routes/auth.ts} do Node e foi portada para manter a equivalencia,
 * mas o frontend nao a usa: o painel tem um unico administrador, criado pelo
 * seed. Esta registrada no README como candidata a remocao no corte — uma
 * rota publica que cria conta e superficie de ataque sem contrapartida.
 *
 * <p>A conta nasce com {@link com.cauacosenza.barbearia.domain.enums.Role#CLIENT},
 * que e o default da coluna, e portanto <b>nao alcanca</b> {@code /admin}:
 * quem se cadastrar aqui recebe 403 em toda rota do painel.
 *
 * <p>As regras de senha sao as do {@code CreateUserSchema} (Zod): minimo 8
 * caracteres, com maiuscula, digito e caractere especial. O telefone usa o
 * {@code phoneSchema} geral ({@code /^\+?[1-9]\d{7,14}$/}, que admite
 * {@code +}), e <b>nao</b> o da reserva publica, que exige digitos puros — as
 * duas rotas sempre tiveram regras diferentes no Node.
 */
public record RegisterRequest(

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Size(min = 2, max = 100)
		String name,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Email(message = "Invalid email")
		String email,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.TELEFONE_INTERNACIONAL, message = "Invalid phone")
		String phone,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Size(min = 8, message = "String must contain at least 8 character(s)")
		@Pattern(regexp = ".*[A-Z].*", message = "Must contain uppercase")
		@Pattern(regexp = ".*[0-9].*", message = "Must contain number")
		@Pattern(regexp = ".*[^A-Za-z0-9].*", message = "Must contain special character")
		String password,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		String confirmPassword) {

	/**
	 * O {@code .refine} do Zod, que reporta o erro em
	 * {@code confirmPassword} — dai o nome deste metodo.
	 *
	 * <p>Nulo passa: quem cobra presenca e o {@code @NotNull}.
	 */
	@AssertTrue(message = "Passwords do not match")
	public boolean isConfirmPassword() {
		if (this.password == null || this.confirmPassword == null) {
			return true;
		}
		return this.password.equals(this.confirmPassword);
	}

}
