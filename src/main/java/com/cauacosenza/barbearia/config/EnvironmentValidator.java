package com.cauacosenza.barbearia.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Valida as variaveis de ambiente no startup e impede a aplicacao de subir se
 * alguma obrigatoria estiver ausente ou malformada — mesmo comportamento do
 * backend Node, que valida com Zod e mata o processo.
 *
 * <p>Duas regras guiam a implementacao:
 *
 * <ul>
 *   <li>Todas as falhas sao coletadas e reportadas de uma vez. Abortar na
 *       primeira esconde as demais e obriga a descobrir o problema por
 *       tentativa e erro.
 *   <li>A mensagem de erro cita apenas o <b>nome</b> da variavel e o motivo,
 *       nunca o valor. Logar um JWT_SECRET invalido e vaza-lo no log.
 * </ul>
 *
 * <p>Desativado no perfil {@code test}, onde datasource e Redis sao fornecidos
 * pelo Testcontainers e nao por variaveis de ambiente.
 *
 * <p><b>Por que um {@link BeanFactoryPostProcessor} e nao um
 * {@code @PostConstruct}:</b> a validacao precisa acontecer antes de qualquer
 * bean ser criado. Com {@code @PostConstruct}, a auto-configuracao do
 * DataSource falha primeiro quando {@code DATABASE_URL} esta ausente, e o
 * desenvolvedor recebe "Failed to determine a suitable driver class" em vez da
 * lista do que esta faltando. Um BeanFactoryPostProcessor roda antes da
 * instanciacao dos singletons, entao esta mensagem e sempre a primeira.
 */
@Component
@Profile("!test")
public class EnvironmentValidator implements BeanFactoryPostProcessor, EnvironmentAware {

	private static final int JWT_SECRET_MIN_LENGTH = 32;

	/** AES-256 usa chave de 32 bytes, que em hexadecimal sao 64 caracteres. */
	private static final int ENCRYPTION_KEY_HEX_LENGTH = 64;

	private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]+$");

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		validate();
	}

	void validate() {
		List<String> problems = new ArrayList<>();

		require("DATABASE_URL", problems);
		require("REDIS_URL", problems);

		requireWith("JWT_SECRET", problems,
				value -> value.length() >= JWT_SECRET_MIN_LENGTH,
				"deve ter no minimo " + JWT_SECRET_MIN_LENGTH + " caracteres");

		requireWith("JWT_REFRESH_SECRET", problems,
				value -> value.length() >= JWT_SECRET_MIN_LENGTH,
				"deve ter no minimo " + JWT_SECRET_MIN_LENGTH + " caracteres");

		requireWith("ENCRYPTION_KEY", problems,
				EnvironmentValidator::isAes256HexKey,
				"deve ter exatamente " + ENCRYPTION_KEY_HEX_LENGTH
						+ " caracteres hexadecimais (chave AES-256 de 32 bytes)");

		requireWith("CORS_ORIGIN", problems,
				EnvironmentValidator::isOriginList,
				"deve conter uma ou mais URLs http/https validas separadas por virgula");

		// FRONTEND_URL, EVOLUTION_API_URL, EVOLUTION_API_KEY,
		// EVOLUTION_INSTANCE_NAME e BARBER_PHONE sao opcionais e nao
		// participam da validacao de startup.

		if (!problems.isEmpty()) {
			throw new IllegalStateException(buildMessage(problems));
		}
	}

	private void require(String name, List<String> problems) {
		if (isBlank(environment.getProperty(name))) {
			problems.add(name + ": obrigatoria, nao definida");
		}
	}

	private void requireWith(String name, List<String> problems, Predicate<String> valid, String requirement) {
		String value = environment.getProperty(name);
		if (isBlank(value)) {
			problems.add(name + ": obrigatoria, nao definida");
		}
		else if (!valid.test(value)) {
			problems.add(name + ": " + requirement);
		}
	}

	private static String buildMessage(List<String> problems) {
		StringBuilder message = new StringBuilder();
		message.append("Configuracao de ambiente invalida — a aplicacao nao pode iniciar.").append(System.lineSeparator());
		message.append(problems.size()).append(problems.size() == 1 ? " variavel" : " variaveis")
				.append(" com problema:").append(System.lineSeparator());
		for (String problem : problems) {
			message.append("  - ").append(problem).append(System.lineSeparator());
		}
		message.append("Corrija as variaveis acima (veja .env.example) e inicie novamente.");
		return message.toString();
	}

	private static boolean isAes256HexKey(String value) {
		return value.length() == ENCRYPTION_KEY_HEX_LENGTH && HEX.matcher(value).matches();
	}

	private static boolean isOriginList(String value) {
		String[] origins = value.split(",");
		for (String origin : origins) {
			if (!isHttpUrl(origin.trim())) {
				return false;
			}
		}
		return origins.length > 0;
	}

	private static boolean isHttpUrl(String value) {
		if (isBlank(value)) {
			return false;
		}
		try {
			URI uri = new URI(value);
			String scheme = uri.getScheme();
			return uri.isAbsolute()
					&& uri.getHost() != null
					&& ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
