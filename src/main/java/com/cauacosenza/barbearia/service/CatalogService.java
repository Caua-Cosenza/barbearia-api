package com.cauacosenza.barbearia.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.dto.response.ProfessionalResponse;
import com.cauacosenza.barbearia.dto.response.ServiceResponse;
import com.cauacosenza.barbearia.repository.BarberRepository;
import com.cauacosenza.barbearia.repository.BarberScheduleRepository;
import com.cauacosenza.barbearia.repository.ServiceRepository;

/**
 * O catalogo publico: a lista de servicos e a de profissionais.
 *
 * <p>As duas rotas mais chamadas do site — a home as dispara no primeiro
 * render — e as duas mais simples: sem regra de negocio, so leitura filtrada
 * por {@code is_active} e conversao para DTO.
 *
 * <p><b>A ordem importa e nao vem de graca.</b> Nenhuma das duas consultas do
 * Node tem {@code orderBy}; o Postgres devolve na ordem do heap, que coincide
 * com a ordem de insercao enquanto ninguem atualiza as linhas. Como o seed
 * cria os servicos na ordem em que o dono quer ve-los na home — os cortes
 * primeiro, os acabamentos no fim — ordenar por nome aqui jogaria
 * "Acabamento / Pe" para o topo e reorganizaria a pagina inteira sem que
 * ninguem tivesse pedido. Por isso {@code createdAt}: reproduz a ordem que o
 * Node entrega hoje e, ao contrario do heap, continua reproduzindo depois de
 * um {@code UPDATE}.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

	private final ServiceRepository services;

	private final BarberRepository barbers;

	private final BarberScheduleRepository schedules;

	public CatalogService(ServiceRepository services, BarberRepository barbers,
			BarberScheduleRepository schedules) {
		this.services = services;
		this.barbers = barbers;
		this.schedules = schedules;
	}

	/** Os servicos ativos, na ordem em que foram cadastrados. */
	public List<ServiceResponse> listarServicos() {
		return this.services.findByActiveTrueOrderByCreatedAtAsc().stream()
				.map(ServiceResponse::de)
				.toList();
	}

	/**
	 * Os profissionais ativos, cada um com a sua jornada semanal.
	 *
	 * <p>Uma consulta de jornada por profissional. Sao dois profissionais em
	 * producao, e a rota e cacheavel; trocar isso por um {@code join fetch}
	 * agora seria otimizar o que nao aperta.
	 */
	public List<ProfessionalResponse> listarProfissionais() {
		List<Barber> ativos = this.barbers.findByActiveTrueOrderByCreatedAtAsc();
		return ativos.stream()
				.map(barbeiro -> ProfessionalResponse.de(barbeiro,
						this.schedules.findByProfessionalIdOrderByDayOfWeekAsc(barbeiro.getId())))
				.toList();
	}

}
