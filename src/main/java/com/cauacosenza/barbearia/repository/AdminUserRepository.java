package com.cauacosenza.barbearia.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cauacosenza.barbearia.domain.entity.AdminUser;

/**
 * Consultas sobre {@code users}.
 *
 * <p>A busca e por {@code email} porque e a credencial de login e tem indice
 * unico. Note que o e-mail e a <b>unica</b> coluna de identificacao que nao e
 * cifrada: nome e telefone vao cifrados, e procurar por eles seria impossivel
 * (ver {@code EncryptedStringConverter}). Cifrar o e-mail quebraria o login.
 */
public interface AdminUserRepository extends JpaRepository<AdminUser, String> {

	Optional<AdminUser> findByEmail(String email);

}
