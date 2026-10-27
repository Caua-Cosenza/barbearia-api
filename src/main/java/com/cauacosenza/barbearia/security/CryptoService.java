package com.cauacosenza.barbearia.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cauacosenza.barbearia.domain.exception.CryptoException;

/**
 * Cifra e decifra a PII dos clientes (nome, telefone, e-mail) no mesmo formato
 * que o backend Node usa em producao.
 *
 * <p>Esta classe e o ponto em que a migracao e viavel ou nao: as colunas
 * {@code name_encrypted}, {@code phone_encrypted} e {@code guest_*_encrypted}
 * ja contem dados cifrados pelo Node, e o Java precisa le-los byte a byte.
 *
 * <p><b>Formato</b> — identico ao do Node:
 *
 * <pre>
 *   hex(iv) + ":" + hex(ciphertext)
 * </pre>
 *
 * AES-256-CBC, IV aleatorio de 16 bytes <i>novo por registro</i>, padding
 * PKCS#7 (no Java chamado {@code PKCS5Padding} — para AES sao o mesmo
 * esquema, blocos de 16 bytes), plaintext em UTF-8.
 *
 * <p><b>Thread-safety</b> — {@link Cipher} nao e thread-safe, portanto nunca e
 * guardado em campo: cada chamada obtem a sua instancia. {@link SecureRandom}
 * e thread-safe e pode ser compartilhado. A chave e imutavel apos a
 * construcao, o que torna o servico seguro para uso concorrente.
 */
@Component
public class CryptoService {

	private static final String ALGORITHM = "AES";

	/** PKCS5Padding no JCE e o PKCS#7 do Node quando o bloco tem 16 bytes. */
	private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

	/** AES-256: 32 bytes de chave. */
	private static final int KEY_LENGTH_BYTES = 32;

	/** CBC com AES: IV do tamanho do bloco. */
	private static final int IV_LENGTH_BYTES = 16;

	private static final char SEPARATOR = ':';

	private static final HexFormat HEX = HexFormat.of();

	private final SecretKey key;

	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * @param encryptionKeyHex a {@code ENCRYPTION_KEY}, 64 caracteres
	 *     hexadecimais. O formato ja e validado no boot pelo
	 *     {@code EnvironmentValidator}; a checagem aqui existe para que o
	 *     servico tambem seja seguro quando instanciado direto, como nos
	 *     testes.
	 */
	public CryptoService(@Value("${ENCRYPTION_KEY}") String encryptionKeyHex) {
		this.key = new SecretKeySpec(decodeKey(encryptionKeyHex), ALGORITHM);
	}

	/**
	 * Cifra um texto, gerando um IV novo.
	 *
	 * @return {@code hex(iv) + ":" + hex(ciphertext)}
	 */
	public String encrypt(String plaintext) {
		if (plaintext == null) {
			throw new CryptoException("Nao e possivel cifrar um valor nulo");
		}
		byte[] iv = new byte[IV_LENGTH_BYTES];
		this.secureRandom.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new IvParameterSpec(iv));
			byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return HEX.formatHex(iv) + SEPARATOR + HEX.formatHex(encrypted);
		}
		catch (GeneralSecurityException ex) {
			// Sem ex como causa e sem o plaintext: a mensagem do JCE pode
			// carregar dado da entrada.
			throw new CryptoException("Falha ao cifrar: " + ex.getClass().getSimpleName());
		}
	}

	/**
	 * Decifra um valor no formato produzido pelo {@link #encrypt(String)} ou
	 * pelo backend Node.
	 *
	 * @throws CryptoException se o formato, o hex, o IV ou a chave estiverem
	 *     errados. A mensagem nunca inclui trecho algum da entrada.
	 */
	public String decrypt(String ciphertext) {
		if (ciphertext == null) {
			throw new CryptoException("Nao e possivel decifrar um valor nulo");
		}

		int separator = ciphertext.indexOf(SEPARATOR);
		if (separator < 0) {
			throw new CryptoException("Formato invalido: separador ':' ausente");
		}
		// Mais de um ':' nunca e saida valida de encrypt. O Node descartaria o
		// excedente em silencio e decifraria lixo; aqui a entrada e recusada.
		if (ciphertext.indexOf(SEPARATOR, separator + 1) >= 0) {
			throw new CryptoException("Formato invalido: mais de um separador ':'");
		}

		String ivHex = ciphertext.substring(0, separator);
		String encryptedHex = ciphertext.substring(separator + 1);
		if (ivHex.isEmpty()) {
			throw new CryptoException("Formato invalido: IV ausente");
		}
		if (encryptedHex.isEmpty()) {
			throw new CryptoException("Formato invalido: ciphertext ausente");
		}

		byte[] iv = parseHex(ivHex, "IV");
		byte[] encrypted = parseHex(encryptedHex, "ciphertext");

		if (iv.length != IV_LENGTH_BYTES) {
			throw new CryptoException(
					"IV invalido: esperado " + IV_LENGTH_BYTES + " bytes, recebido " + iv.length);
		}

		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, this.key, new IvParameterSpec(iv));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			// Cobre BadPaddingException (chave errada ou dado corrompido) e
			// IllegalBlockSizeException (tamanho nao multiplo do bloco).
			throw new CryptoException("Falha ao decifrar: " + ex.getClass().getSimpleName());
		}
	}

	private static byte[] parseHex(String value, String field) {
		try {
			return HEX.parseHex(value);
		}
		catch (IllegalArgumentException ex) {
			// A mensagem do HexFormat inclui o caractere invalido, por isso ela
			// nao e propagada.
			throw new CryptoException("Hexadecimal invalido no campo " + field);
		}
	}

	private static byte[] decodeKey(String encryptionKeyHex) {
		if (encryptionKeyHex == null || encryptionKeyHex.isBlank()) {
			throw new CryptoException("ENCRYPTION_KEY nao definida");
		}
		byte[] decoded;
		try {
			decoded = HEX.parseHex(encryptionKeyHex);
		}
		catch (IllegalArgumentException ex) {
			throw new CryptoException("ENCRYPTION_KEY nao e hexadecimal valido");
		}
		if (decoded.length != KEY_LENGTH_BYTES) {
			throw new CryptoException("ENCRYPTION_KEY invalida: esperado " + KEY_LENGTH_BYTES
					+ " bytes (" + (KEY_LENGTH_BYTES * 2) + " caracteres hex), recebido " + decoded.length);
		}
		return decoded;
	}

}
