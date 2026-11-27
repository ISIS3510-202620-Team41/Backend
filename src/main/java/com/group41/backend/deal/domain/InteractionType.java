package com.group41.backend.deal.domain;

/**
 * Que hizo el estudiante con un cupon.
 *
 * Los tres estados son los tres pasos del embudo que mide la BQ-11, y hacen falta los
 * tres: la pregunta es por una TASA, y una tasa necesita denominador. Contar solo las
 * redenciones diria cuantas hubo, pero no si fueron muchas o pocas respecto a cuantos
 * cupones se mostraron.
 */
public enum InteractionType {
    /** El cupon aparecio en la lista. Es el denominador. */
    SHOWN,
    /** El estudiante lo abrio para ver el detalle. */
    OPENED,
    /** Lo redimio. Es el numerador. */
    REDEEMED
}
