package com.cauacosenza.barbearia.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cauacosenza.barbearia.domain.entity.Service;

/**
 * Consultas sobre {@code services}.
 *
 * <p>{@link #findByIdInAndActiveTrue(Collection)} atende a reserva com varios
 * servicos: o cliente manda uma lista de ids e a camada de servico compara o
 * tamanho do resultado com o tamanho do pedido. Id inexistente e id de servico
 * desativado somem do resultado do mesmo jeito, e a diferenca de tamanho e o
 * sinal de que o pedido e invalido.
 */
public interface ServiceRepository extends JpaRepository<Service, String> {

	List<Service> findByActiveTrueOrderByNameAsc();

	List<Service> findByIdInAndActiveTrue(Collection<String> ids);

	Optional<Service> findByIdAndActiveTrue(String id);

}
