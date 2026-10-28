package com.cauacosenza.barbearia.config;

import java.time.Duration;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * O armazenamento distribuido dos baldes de rate limit.
 *
 * <p><b>Redis e nao memoria.</b> Em producao a aplicacao roda em mais de uma
 * instancia, e um balde em memoria significaria um limite por instancia: com
 * duas replicas, 30 por minuto viram 60 na pratica, e o limite para de ser
 * limite. O Node ja usava Redis pelo mesmo motivo.
 *
 * <p>O {@link RedisClient} aqui e proprio, separado do
 * {@code LettuceConnectionFactory} do Spring Data, porque o Bucket4j precisa
 * de uma conexao com codec de {@code byte[]} no valor — ele serializa o estado
 * do balde em binario. Os dados de conexao vem do mesmo
 * {@link DataRedisConnectionDetails} que o Spring usa, entao {@code REDIS_URL}
 * em producao e o container do Testcontainers no teste funcionam sem
 * configuracao extra.
 *
 * <p>A expiracao de uma hora e higiene: sem ela, cada IP que passar uma vez
 * pelo site deixa uma chave no Redis para sempre. Uma hora e folgada em
 * relacao a janela de um minuto dos baldes.
 */
@Configuration
public class RateLimitConfig {

	private static final Duration EXPIRACAO_DA_CHAVE = Duration.ofHours(1);

	@Bean(destroyMethod = "shutdown")
	RedisClient rateLimitRedisClient(DataRedisConnectionDetails conexao) {
		DataRedisConnectionDetails.Standalone standalone = conexao.getStandalone();

		RedisURI.Builder uri = RedisURI.builder()
				.withHost(standalone.getHost())
				.withPort(standalone.getPort())
				.withDatabase(standalone.getDatabase());

		if (StringUtils.hasText(conexao.getPassword())) {
			if (StringUtils.hasText(conexao.getUsername())) {
				uri.withAuthentication(conexao.getUsername(), conexao.getPassword().toCharArray());
			}
			else {
				uri.withPassword(conexao.getPassword().toCharArray());
			}
		}

		return RedisClient.create(uri.build());
	}

	/**
	 * O {@link ProxyManager} que o filtro usa para pegar o balde de cada
	 * chave.
	 *
	 * <p>Chave em {@code String} e valor em {@code byte[]}: a chave fica
	 * legivel no Redis (util para depurar quem esta sendo limitado) e o valor e
	 * o estado binario do balde.
	 */
	@Bean
	ProxyManager<String> rateLimitProxyManager(RedisClient client) {
		RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
		return LettuceBasedProxyManager.builderFor(client.connect(codec))
				.withExpirationStrategy(
						ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(EXPIRACAO_DA_CHAVE))
				.build();
	}

}
