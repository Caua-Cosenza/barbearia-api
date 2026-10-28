package com.cauacosenza.barbearia.domain.enums;

/**
 * Espelha o tipo enumerado {@code "AppointmentStatus"} do Postgres.
 *
 * <p>A ordem das constantes e a mesma do banco e nao e decorativa: o Postgres
 * guarda um {@code enumsortorder} por valor, usado em comparacao e
 * {@code ORDER BY}. {@code NO_SHOW} e o ultimo porque foi acrescentado depois
 * dos outros quatro. Reordenar aqui nao muda o banco, mas desalinha o codigo
 * da fonte da verdade — e {@link #ordinal()} passaria a mentir.
 *
 * <p>Somente {@link #PENDING} e {@link #CONFIRMED} ocupam agenda. Essa
 * distincao aparece em dois lugares, e os dois precisam concordar: no filtro
 * das consultas que alimentam o calculo de horarios e no {@code WHERE} da
 * constraint de exclusao criada na V2.
 */
public enum AppointmentStatus {

	PENDING,

	CONFIRMED,

	CANCELLED,

	COMPLETED,

	NO_SHOW;

	/**
	 * {@code true} quando um agendamento neste status bloqueia o horario.
	 *
	 * <p>Cancelado, concluido e no-show nao bloqueiam: o horario de um
	 * agendamento cancelado volta a ser vendavel, e um atendimento que ja
	 * terminou nao impede uma nova reserva no mesmo horario de outro dia.
	 */
	public boolean ocupaAgenda() {
		return this == PENDING || this == CONFIRMED;
	}

}
