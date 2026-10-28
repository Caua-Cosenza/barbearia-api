package com.cauacosenza.barbearia.dto.response;

/**
 * Corpo de {@code PUT /api/v1/admin/appointments/{id}/status}.
 *
 * <p>So id e status: o painel ja tem o resto da linha em memoria e aplica a
 * mudanca localmente ({@code setAppointments(prev => prev.map(...))}), sem
 * recarregar a lista.
 */
public record UpdateAppointmentStatusResponse(String id, String status) {

}
