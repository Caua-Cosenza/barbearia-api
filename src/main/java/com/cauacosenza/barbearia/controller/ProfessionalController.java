package com.cauacosenza.barbearia.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.ProfessionalResponse;
import com.cauacosenza.barbearia.service.CatalogService;

/**
 * {@code GET /api/v1/professionals} — profissionais ativos com a jornada
 * semanal embutida.
 *
 * <p>A jornada vem junto porque o frontend precisa dela <b>antes</b> de falar
 * com o servidor de novo: {@code isCalDayDisabled} desabilita no calendario os
 * dias sem {@code availability}, sem nenhuma chamada extra.
 */
@RestController
@RequestMapping("/api/v1/professionals")
public class ProfessionalController {

	private final CatalogService catalogo;

	public ProfessionalController(CatalogService catalogo) {
		this.catalogo = catalogo;
	}

	@GetMapping
	public ApiResponse<List<ProfessionalResponse>> listar() {
		return ApiResponse.ok(this.catalogo.listarProfissionais());
	}

}
