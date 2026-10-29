package com.deroyque.ativacao.dto;

import java.util.List;

public record PaginaDTO<T>(

        List<T> content,

        Integer page,

        Integer size,

        Long totalElements,

        Integer totalPages) {

}
