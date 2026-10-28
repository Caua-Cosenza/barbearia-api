package com.cauacosenza.barbearia.controller;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.request.CreateBlockedSlotRequest;
import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.BlockedSlotResponse;
import com.cauacosenza.barbearia.service.BlockedSlotService;

/**
 * {@code /api/v1/admin/blocked-slots} — o "reservar horario" do painel.
 *
 * <p>Rota de admin: exige {@code ROLE_ADMIN} como o resto de
 * {@code /api/v1/admin/**}.
 */
@RestController
@RequestMapping("/api/v1/admin/blocked-slots")
@Validated
public class BlockedSlotController {

	private static final String DATA_ISO = "^\\d{4}-\\d{2}-\\d{2}$";

	private static final String UUID_REGEX =
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

	private final BlockedSlotService bloqueios;

	public BlockedSlotController(BlockedSlotService bloqueios) {
		this.bloqueios = bloqueios;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<BlockedSlotResponse> criar(@Valid @RequestBody CreateBlockedSlotRequest pedido) {
		return ApiResponse.ok(this.bloqueios.criar(pedido), "Slot blocked");
	}

	/**
	 * Os bloqueios de um profissional em um dia.
	 *
	 * <p><b>Os dois parametros sao obrigatorios</b>, como no
	 * {@code listQuerySchema} do Node — ao contrario de
	 * {@code GET /admin/appointments}, onde {@code date} e opcional. Sem
	 * {@code professionalId} a consulta devolveria os bloqueios de todo mundo,
	 * e o painel mostra a agenda de um profissional por vez.
	 */
	@GetMapping
	public ApiResponse<List<BlockedSlotResponse>> listar(
			@RequestParam(name = "date")
			@Pattern(regexp = DATA_ISO, message = "Invalid") String date,
			@RequestParam(name = "professionalId")
			@Pattern(regexp = UUID_REGEX, message = "Invalid uuid") String professionalId) {

		return ApiResponse.ok(this.bloqueios.listar(professionalId, LocalDate.parse(date)));
	}

	/** Remove o bloqueio e devolve o horario a venda. 404 se nao existir. */
	@DeleteMapping("/{id}")
	public ApiResponse<Void> remover(
			@PathVariable @Pattern(regexp = UUID_REGEX, message = "Invalid uuid") String id) {

		this.bloqueios.remover(id);
		return ApiResponse.ok(null, "Blocked slot removed");
	}

}
