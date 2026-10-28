package com.cauacosenza.barbearia.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.stereotype.Component;

/**
 * Faz a PII dos clientes ir e voltar do banco cifrada, sem que o resto do
 * codigo precise saber disso: a entity tem um {@code String} em texto claro, a
 * coluna {@code *_encrypted} guarda {@code hex(iv):hex(ciphertext)} no formato
 * do backend Node.
 *
 * <p>Aplicado explicitamente campo a campo com {@code @Convert}, nunca por
 * {@code autoApply}: um converter de {@code String} para {@code String} com
 * autoApply pegaria <i>toda</i> coluna de texto do schema — e-mail, nome de
 * servico, token — e cifraria o banco inteiro.
 *
 * <p><b>Nulo atravessa intacto.</b> As colunas {@code guest_*_encrypted} sao
 * NULLABLE: um agendamento de cliente logado nao tem PII de visitante. O
 * {@link CryptoService} recusa nulo com {@code CryptoException} de proposito
 * (cifrar nulo e sempre bug), entao a checagem tem que estar aqui. Note que
 * nulo e diferente de string vazia: {@code ""} e cifrado normalmente, e volta
 * como {@code ""}.
 *
 * <p><b>Por que isto e um bean.</b> {@code AttributeConverter} e instanciado
 * pelo Hibernate, nao pelo Spring — e por padrao sairia com
 * {@code cryptoService} nulo. Funciona porque o Spring Boot registra o
 * {@code SpringBeanContainer} como {@code hibernate.resource.beans.container},
 * e o Hibernate passa a pedir a instancia ao container do Spring. Dai a
 * injecao por construtor, sem campo estatico, sem {@code ApplicationContextAware}:
 * se o wiring quebrar, quebra no boot e nao em producao na primeira leitura.
 * {@code EncryptedStringConverterTest} existe para provar que a injecao
 * acontece de verdade.
 *
 * <p><b>Consequencia a aceitar de olhos abertos:</b> o ciphertext tem IV novo
 * por gravacao, portanto o mesmo nome produz bytes diferentes a cada vez. Nao
 * existe {@code WHERE name_encrypted = ?}, nem {@code LIKE}, nem
 * {@code ORDER BY} em coluna cifrada. Buscar ou ordenar por PII exige carregar
 * e decifrar em memoria.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private final CryptoService cryptoService;

	public EncryptedStringConverter(CryptoService cryptoService) {
		this.cryptoService = cryptoService;
	}

	@Override
	public String convertToDatabaseColumn(String plaintext) {
		return (plaintext == null) ? null : this.cryptoService.encrypt(plaintext);
	}

	@Override
	public String convertToEntityAttribute(String ciphertext) {
		return (ciphertext == null) ? null : this.cryptoService.decrypt(ciphertext);
	}

}
