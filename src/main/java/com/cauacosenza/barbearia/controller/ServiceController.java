package com.cauacosenza.barbearia.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cauacosenza.barbearia.dto.response.ApiResponse;
import com.cauacosenza.barbearia.dto.response.ServiceResponse;
import com.cauacosenza.barbearia.service.CatalogService;

/**
 * {@code GET /api/v1/services} — o catalogo que a home e o modal exibem.
 *
 * <p>Sem {@code try/catch}: o {@code GlobalExceptionHandler} traduz qualquer
 * falha para o envelope. E a regra de todos os controllers deste projeto.
 */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

	private final CatalogService catalogo;

	public ServiceController(CatalogService catalogo) {
		this.catalogo = catalogo;
	}

	@GetMapping
	public ApiResponse<List<ServiceResponse>> listar() {
		return ApiResponse.ok(this.catalogo.listarServicos());
	}

}
