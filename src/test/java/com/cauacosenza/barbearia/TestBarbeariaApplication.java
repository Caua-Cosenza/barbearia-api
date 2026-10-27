package com.cauacosenza.barbearia;

import org.springframework.boot.SpringApplication;

import com.cauacosenza.barbearia.integration.TestcontainersConfiguration;

/**
 * Roda a aplicacao localmente com Postgres e Redis em containers efemeros,
 * sem precisar de docker compose nem de .env.
 *
 * <p>Atalho de desenvolvimento: execute esta classe pela IDE.
 */
public class TestBarbeariaApplication {

	public static void main(String[] args) {
		System.setProperty("spring.profiles.active", "test");
		SpringApplication.from(BarbeariaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
