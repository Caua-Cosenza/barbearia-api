package com.cauacosenza.barbearia.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cauacosenza.barbearia.domain.entity.Barber;

/**
 * Consultas sobre {@code professionals}.
 *
 * <p>Os dois metodos filtram por {@code is_active}. Profissional inativo nao e
 * apagado — o historico de agendamentos aponta para ele, e a FK e
 * {@code ON DELETE RESTRICT} — entao a linha continua no banco e some apenas
 * das listagens. Por isso nao existe aqui um {@code findById} sem filtro: usar
 * o herdado do {@link JpaRepository} em fluxo publico reabriria a agenda de
 * quem saiu.
 */
public interface BarberRepository extends JpaRepository<Barber, String> {

	List<Barber> findByActiveTrueOrderByCreatedAtAsc();

	Optional<Barber> findByIdAndActiveTrue(String id);

}
