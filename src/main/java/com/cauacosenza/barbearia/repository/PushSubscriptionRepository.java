package com.cauacosenza.barbearia.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cauacosenza.barbearia.domain.entity.PushSubscription;

/**
 * Consultas sobre {@code push_subscriptions}.
 *
 * <p>A busca por {@code endpoint} existe para o upsert: o navegador pode
 * reinscrever o mesmo dispositivo a qualquer momento, e a coluna tem indice
 * unico. Sem procurar antes, a segunda inscricao violaria a constraint.
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, String> {

	Optional<PushSubscription> findByEndpoint(String endpoint);

}
