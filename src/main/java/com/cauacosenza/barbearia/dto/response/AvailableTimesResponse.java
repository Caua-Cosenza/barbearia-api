package com.cauacosenza.barbearia.dto.response;

import java.util.List;

/**
 * Resposta de {@code POST /api/v1/available-times}.
 *
 * <p>Um objeto com uma unica lista, e nao a lista nua, porque e o que o
 * frontend espera: {@code res.data?.slots ?? []}.
 *
 * <p>Os horarios sao strings {@code "HH:MM"} em hora de Brasilia, em ordem
 * crescente. Lista vazia e resposta valida e frequente — e o que sai quando o
 * profissional nao atende naquele dia da semana.
 */
public record AvailableTimesResponse(List<String> slots) {

}
