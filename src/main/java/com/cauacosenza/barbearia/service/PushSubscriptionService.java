package com.cauacosenza.barbearia.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.PushSubscription;
import com.cauacosenza.barbearia.dto.request.PushSubscriptionRequest;
import com.cauacosenza.barbearia.repository.PushSubscriptionRepository;

/**
 * Guarda a inscricao Web Push do dispositivo do dono.
 *
 * <p>E rota de <b>admin</b>, nao publica — descoberto ao ler o
 * {@code routes/admin.ts} na Fase 5. Faz sentido: quem recebe a notificacao de
 * "novo agendamento" e o balcao, nao o cliente.
 *
 * <p>A operacao e um <b>upsert por {@code endpoint}</b>. O navegador
 * reinscreve o mesmo dispositivo por conta propria — quando a inscricao
 * expira, quando o service worker e atualizado — e a coluna tem indice unico.
 * Sem procurar antes, a segunda inscricao do mesmo aparelho violaria a
 * constraint e o dono pararia de receber aviso sem nunca saber por que.
 */
@Service
public class PushSubscriptionService {

	private final PushSubscriptionRepository inscricoes;

	public PushSubscriptionService(PushSubscriptionRepository inscricoes) {
		this.inscricoes = inscricoes;
	}

	/** Cria ou atualiza as chaves do endpoint. Responde 201 nos dois casos. */
	@Transactional
	public void salvar(PushSubscriptionRequest pedido) {
		PushSubscription inscricao = this.inscricoes.findByEndpoint(pedido.endpoint())
				.orElseGet(() -> {
					PushSubscription nova = new PushSubscription();
					nova.setEndpoint(pedido.endpoint());
					return nova;
				});

		// As chaves mudam a cada reinscricao: atualizar as duas e o ponto do
		// upsert, e nao apenas evitar a violacao de unicidade.
		inscricao.setP256dh(pedido.keys().p256dh());
		inscricao.setAuth(pedido.keys().auth());

		this.inscricoes.saveAndFlush(inscricao);
	}

}
