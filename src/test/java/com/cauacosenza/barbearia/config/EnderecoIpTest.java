package com.cauacosenza.barbearia.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * O reconhecedor de literal de IP.
 *
 * <p>Tem teste proprio porque e <b>parser escrito a mao</b>: o projeto nao tem
 * Guava nem commons-validator e {@code InetAddress.ofLiteral} so existe no
 * Java 22. Parser de IPv6 escrito a mao e exatamente o tipo de codigo que
 * parece certo e erra em compressao, sufixo IPv4 e contagem de grupos.
 *
 * <p>E o que esta em jogo nao e cosmetico: o valor aprovado aqui vira chave de
 * balde no Redis e linha de log. Aceitar o que nao e IP devolve as duas
 * brechas que a validacao fechou — cardinalidade ilimitada de chave e injecao
 * de log. Recusar um IP legitimo joga um cliente real para o balde do
 * {@code remoteAddr}, que e compartilhado.
 */
class EnderecoIpTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"1.2.3.4",
			"0.0.0.0",
			"255.255.255.255",
			"203.0.113.9",
			"100.64.0.3",
			"10.0.0.1" })
	@DisplayName("aceita IPv4")
	void aceitaIpv4(String valor) {
		assertThat(EnderecoIp.valido(valor)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"256.1.1.1",          // octeto fora da faixa
			"1.2.3",              // octetos de menos
			"1.2.3.4.5",          // octetos de mais
			"1.2.3.",             // octeto final vazio
			".1.2.3",             // octeto inicial vazio
			"1.2..4",             // octeto vazio no meio
			"01.2.3.4",           // zero a esquerda: viraria um segundo balde
			"1.2.3.0004",
			"1.2.3.-4",
			"1.2.3.4 ",           // o resolvedor apara antes; aqui nao passa
			"1.2.3.a",
			"1234.1.1.1",
			"localhost",
			"balde-escolhido-a-dedo",
			"teste-8b1f0c22-0000-4000-8000-000000000000" })
	@DisplayName("recusa o que nao e IPv4")
	void recusaIpv4Invalido(String valor) {
		assertThat(EnderecoIp.valido(valor)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"::",
			"::1",
			"1::",
			"2001:db8::1",
			"2001:db8::0:1",
			"2001:0db8:0000:0000:0000:0000:0000:0001",
			"1:2:3:4:5:6:7:8",
			"1:2:3:4:5:6:7::",         // "::" valendo um grupo so
			"fe80::0202:b3ff:fe1e:8329",
			"::ffff:192.168.1.1",      // IPv4 mapeado
			"2001:db8::192.168.1.1",
			"ABCD:EF01:2345:6789:ABCD:EF01:2345:6789" })
	@DisplayName("aceita IPv6")
	void aceitaIpv6(String valor) {
		assertThat(EnderecoIp.valido(valor)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"1:2:3:4:5:6:7",           // grupos de menos, sem compressao
			"1:2:3:4:5:6:7:8:9",       // grupos de mais
			"1:2:3:4:5:6:7:8::",       // ja tem 8 grupos, "::" nao cabe
			"1::2::3",                 // duas compressoes: ambiguo
			"1:::2",
			":1:2:3:4:5:6:7",          // dois-pontos solto na ponta
			"1:2:3:4:5:6:7:",
			"12345::1",                // grupo com 5 digitos
			"1:2:3:g::",               // digito nao hexadecimal
			"fe80::1%eth0",            // identificador de zona
			"::ffff:192.168.1.256",    // sufixo IPv4 invalido
			"::ffff:1.2.3",
			"1.2.3.4::",
			"::192.168.1.1:ffff" })    // sufixo IPv4 fora do fim
	@DisplayName("recusa o que nao e IPv6")
	void recusaIpv6Invalido(String valor) {
		assertThat(EnderecoIp.valido(valor)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@DisplayName("recusa nulo e vazio")
	void recusaNuloEVazio(String valor) {
		assertThat(EnderecoIp.valido(valor)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"203.0.113.9\nINFO  linha inventada pelo cliente",
			"203.0.113.9\r\nWARN  outra",
			"2001:db8::1\nERROR" })
	@DisplayName("recusa valor com quebra de linha — o que injetava log")
	void recusaQuebraDeLinha(String valor) {
		// trim() nao resolvia isto: ele remove as pontas, e a quebra estava no
		// meio. O valor chegava inteiro no log do RateLimitFilter.
		assertThat(EnderecoIp.valido(valor)).isFalse();
	}

}
