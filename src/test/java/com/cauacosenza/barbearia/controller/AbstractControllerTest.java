package com.cauacosenza.barbearia.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cauacosenza.barbearia.integration.RequisicaoDaBorda;
import com.cauacosenza.barbearia.integration.SegredosDeTeste;
import com.cauacosenza.barbearia.integration.TestcontainersConfiguration;
import com.cauacosenza.barbearia.service.AvailableTimesService;
import com.cauacosenza.barbearia.service.CancelService;
import com.cauacosenza.barbearia.service.CatalogService;
import com.cauacosenza.barbearia.service.PublicAppointmentService;

/**
 * Base dos testes de borda HTTP, com os services substituidos por mocks.
 *
 * <p><b>Nao e {@code @WebMvcTest}.</b> A intencao era essa, e nao funciona
 * aqui: esta aplicacao registra dois {@code OncePerRequestFilter} como
 * {@code @Component} — o rate limit e o que recusa preco no corpo — e o
 * {@code @WebMvcTest} os carrega junto com a cadeia web, levando consigo o
 * {@code ProxyManager} do Bucket4j e, com ele, o Redis. A saida e
 * {@code @SpringBootTest} com {@code webEnvironment=MOCK}: sobe mais coisa,
 * mas o banco nao e tocado, porque toda leitura e escrita passa pelos quatro
 * services e os quatro estao mockados. Rotas, filtros, serializacao do Jackson
 * e {@code GlobalExceptionHandler} sao exercitados de verdade, que e o que
 * estes testes querem cobrir.
 *
 * <p><b>{@code @MockitoBean} e nao um {@code @Bean} {@code @Primary}.</b> A
 * primeira versao destes testes usava uma {@code @TestConfiguration} com mocks
 * marcados {@code @Primary}, e o resultado foi silenciosamente errado: o campo
 * {@code @Autowired} do teste recebia o bean <b>real</b> (envolto no proxy
 * CGLIB da transacao), entao {@code given(service.metodo(any()))} <i>executava
 * o metodo de verdade</i> durante a montagem do stub — e o teste falhava com a
 * excecao da regra de negocio em vez de testar o mapeamento dela.
 * {@code @MockitoBean} substitui a definicao do bean no contexto, em vez de
 * competir com ela, e nao ha ambiguidade a resolver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = { SegredosDeTeste.ENCRYPTION_KEY, SegredosDeTeste.JWT_SECRET,
				SegredosDeTeste.JWT_REFRESH_SECRET, SegredosDeTeste.EDGE_TOKEN })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
abstract class AbstractControllerTest {

	@MockitoBean
	protected CatalogService catalogo;

	@MockitoBean
	protected AvailableTimesService horarios;

	@MockitoBean
	protected PublicAppointmentService agendamentos;

	@MockitoBean
	protected CancelService cancelamentos;

	/**
	 * Monta um POST com corpo JSON, token CSRF e <b>um IP novo a cada
	 * chamada</b>.
	 *
	 * <p>O IP unico nao e paranoia: o rate limit e real nestes testes, a
	 * criacao de agendamento tem balde de 10 por minuto, e esta classe faz
	 * mais de 10 POSTs. Sem variar o IP, os ultimos testes recebem 429 e
	 * falham por um motivo que nada tem a ver com o que estao verificando —
	 * foi exatamente o que aconteceu na primeira execucao. Dar a cada
	 * requisicao a sua chave isola os baldes; o limite em si tem teste proprio,
	 * onde o IP e fixo de proposito. Ver {@link RequisicaoDaBorda}.
	 */
	protected static MockHttpServletRequestBuilder postJson(String url, String corpo) {
		return RequisicaoDaBorda.comIpProprio(post(url))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(corpo);
	}

	/** GET com IP novo, pelo mesmo motivo. */
	protected static MockHttpServletRequestBuilder getComIpProprio(String url) {
		return RequisicaoDaBorda.comIpProprio(get(url));
	}

}
