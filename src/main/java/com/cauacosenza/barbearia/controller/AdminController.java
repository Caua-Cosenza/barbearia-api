package com.cauacosenza.barbearia.controller;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.request.PushSubscriptionRequest;
import com.cauacosenza.barbearia.dto.request.UpdateAppointmentStatusRequest;
import com.cauacosenza.barbearia.dto.response.AdminAppointmentResponse;
import com.cauacosenza.barbearia.dto.response.AdminHealthResponse;
import com.cauacosenza.barbearia.dto.response.AdminStatsResponse;
import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.UpdateAppointmentStatusResponse;
import com.cauacosenza.barbearia.service.AdminService;
import com.cauacosenza.barbearia.service.PushSubscriptionService;

/**
 * {@code /api/v1/admin/*} — o painel do dono. Tudo aqui exige
 * {@code ROLE_ADMIN}, imposto na {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Validated
public class AdminController {

	/** {@code YYYY-MM-DD}, o mesmo do {@code DateQuerySchema} do Node. */
	private static final String DATA_ISO = "^\\d{4}-\\d{2}-\\d{2}$";

	private final AdminService admin;

	private final PushSubscriptionService push;

	public AdminController(AdminService admin, PushSubscriptionService push) {
		this.admin = admin;
		this.push = push;
	}

	/**
	 * O teste de sessao do painel.
	 *
	 * <p>Nao e healthcheck de infraestrutura — esse e o
	 * {@code /actuator/health}, publico. Aqui o 200 significa "ha sessao de
	 * admin" e o <b>401 e resposta esperada</b>: o {@code AuthContext} chama
	 * esta rota no boot porque o cookie e {@code HttpOnly} e o JavaScript nao
	 * consegue inspeciona-lo.
	 */
	@GetMapping("/health")
	public ApiResponse<AdminHealthResponse> health() {
		return ApiResponse.ok(AdminHealthResponse.ok(), "Admin route active");
	}

	/** Os quatro contadores do topo. */
	@GetMapping("/stats")
	public ApiResponse<AdminStatsResponse> estatisticas() {
		return ApiResponse.ok(this.admin.estatisticas());
	}

	/**
	 * A agenda de um dia, com todos os status.
	 *
	 * <p>Sem {@code date}, vale <b>hoje em Brasilia</b>. O Node usa
	 * {@code new Date().toISOString().split('T')[0]} aqui, que e a data
	 * <i>UTC</i> — entre 21:00 e 00:00 de Brasilia isso ja e o dia seguinte, e
	 * o painel abriria na agenda errada. O frontend sempre envia a data, entao
	 * o caminho e inalcancavel por ele; a divergencia esta no README.
	 */
	@GetMapping("/appointments")
	public ApiResponse<List<AdminAppointmentResponse>> agenda(
			@RequestParam(name = "date", required = false)
			@Pattern(regexp = DATA_ISO, message = "date must be YYYY-MM-DD format") String date) {

		LocalDate dia = (date != null) ? LocalDate.parse(date) : this.admin.hojeEmBrasilia();
		return ApiResponse.ok(this.admin.agendaDoDia(dia));
	}

	/**
	 * Finalizar, cancelar ou marcar no-show.
	 *
	 * <p><b>{@code PUT .../{id}/status}, nao {@code PATCH .../{id}}.</b> E o
	 * que o {@code endpoints.ts} chama
	 * ({@code put(`/admin/appointments/${id}/status`, { status })}) e o que o
	 * {@code routes/admin.ts} registra.
	 */
	@PutMapping("/appointments/{id}/status")
	public ApiResponse<UpdateAppointmentStatusResponse> mudarStatus(
			@PathVariable String id,
			@Valid @RequestBody UpdateAppointmentStatusRequest pedido) {

		return ApiResponse.ok(this.admin.mudarStatus(id, pedido), "Status atualizado");
	}

	/** Grava a inscricao Web Push do dispositivo. Responde 201. */
	@PostMapping("/push-subscription")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<Void> inscreverPush(@Valid @RequestBody PushSubscriptionRequest pedido) {
		this.push.salvar(pedido);
		return ApiResponse.ok(null, "Subscription saved");
	}

}
