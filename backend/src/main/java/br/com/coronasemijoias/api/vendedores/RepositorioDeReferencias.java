package br.com.coronasemijoias.api.vendedores;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RepositorioDeReferencias extends JpaRepository<ReferenciaDoVendedor, UUID> {

    List<ReferenciaDoVendedor> findByVendedorIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(UUID vendedorId);
}
