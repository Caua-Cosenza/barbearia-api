package com.cauacosenza.barbearia.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;
import com.cauacosenza.barbearia.domain.exception.BloqueioNoPassadoException;
import com.cauacosenza.barbearia.domain.exception.RecursoNaoEncontradoException;
import com.cauacosenza.barbearia.dto.request.CreateBlockedSlotRequest;
import com.cauacosenza.barbearia.dto.response.BlockedSlotResponse;
import com.cauacosenza.barbearia.repository.BarberRepository;
import com.cauacosenza.barbearia.repository.ScheduleBlockRepository;

/**
 * O "reservar horario" do painel: folga, almoco, imprevisto.
 *
 * <h2>Criar bloqueio sobre horario ja agendado continua permitido</h2>
 *
 * <p>Isto parece um furo e e <b>deliberado</b>. O comportamento correto seria
 * recusar com 409 e a lista dos clientes afetados, exigindo confirmacao
 * explicita — mas o painel React em producao nao sabe tratar esse 409: ele
 * faz {@code if (res.success)} e, no catch, apenas mostra "Erro ao criar
 * bloqueio". Implementar a melhoria agora deixaria o Jhonatan <b>sem conseguir
 * reservar horario nenhum</b>, que e pior do que o furo.
 *
 * <p>A melhoria entra depois do corte, junto com a mudanca no frontend.
 * Registrada no README como trabalho futuro.
 *
 * <p>Na pratica o efeito do furo e limitado: o bloqueio some da lista de
 * horarios livres (ele e filtro no fim do calculo) mas <b>nao cancela</b> o
 * agendamento que ja existe, que continua aparecendo no painel do dia. O dono
 * ve os dois e resolve por telefone.
 */
@Service
public class BlockedSlotService {

	private final ScheduleBlockRepository blocks;

	private final BarberRepository barbers;

	private final AdminService admin;

	public BlockedSlotService(ScheduleBlockRepository blocks, BarberRepository barbers,
			AdminService admin) {
		this.blocks = blocks;
		this.barbers = barbers;
		this.admin = admin;
	}

	/**
	 * Cria o bloqueio. Responde 201.
	 *
	 * <p>A unica recusa e data no passado, comparada em <b>hoje de
	 * Brasilia</b> — como no Node. Comparar contra a data UTC faria o painel
	 * recusar um bloqueio para amanha depois das 21h, que foi exatamente o bug
	 * que motivou a conversao de fuso.
	 *
	 * <p>A comparacao e de string, e funciona porque {@code "YYYY-MM-DD"} de
	 * largura fixa ordena lexicograficamente igual a cronologicamente.
	 */
	@Transactional
	public BlockedSlotResponse criar(CreateBlockedSlotRequest pedido) {
		String hoje = this.admin.hojeEmBrasilia().toString();
		if (pedido.date().compareTo(hoje) < 0) {
			throw new BloqueioNoPassadoException();
		}

		// O Node nao valida se o profissional existe -- o Prisma falharia com
		// erro de FK e o cliente veria 500. Aqui a FK e checada antes, para a
		// resposta ser 404 em vez de defeito.
		Barber profissional = this.barbers.findById(pedido.professionalId())
				.orElseThrow(RecursoNaoEncontradoException::profissional);

		ScheduleBlock bloqueio = new ScheduleBlock();
		bloqueio.setProfessional(profissional);
		bloqueio.setDate(pedido.date());
		bloqueio.setStartTime(pedido.startTime());
		bloqueio.setEndTime(pedido.endTime());
		bloqueio.setReason(pedido.reason());

		return BlockedSlotResponse.de(this.blocks.saveAndFlush(bloqueio));
	}

	/** Os bloqueios de um profissional em um dia, ordenados por inicio. */
	@Transactional(readOnly = true)
	public List<BlockedSlotResponse> listar(String professionalId, LocalDate dia) {
		return this.blocks
				.findByProfessionalIdAndDateOrderByStartTimeAsc(professionalId, dia.toString())
				.stream()
				.map(BlockedSlotResponse::de)
				.toList();
	}

	/**
	 * Remove o bloqueio, devolvendo o horario a venda.
	 *
	 * <p>404 quando nao existe, em vez de 200 silencioso: o painel recarrega a
	 * lista depois de remover, e um sucesso falso faria o item reaparecer sem
	 * explicacao.
	 */
	@Transactional
	public void remover(String id) {
		ScheduleBlock bloqueio = this.blocks.findById(id)
				.orElseThrow(RecursoNaoEncontradoException::bloqueio);
		this.blocks.delete(bloqueio);
	}

}
