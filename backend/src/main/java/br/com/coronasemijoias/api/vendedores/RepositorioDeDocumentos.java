package br.com.coronasemijoias.api.vendedores;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RepositorioDeDocumentos extends JpaRepository<DocumentoDoVendedor, UUID> {

    List<DocumentoDoVendedor> findByVendedorIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(UUID vendedorId);
}
