import { Botao } from './Botao';

export interface PaginacaoProps {
  /** Página atual, a partir de 0. */
  pagina: number;
  tamanho: number;
  total: number;
  aoMudar: (pagina: number) => void;
  /** Palavra no singular e no plural para o total, ex.: ["usuário", "usuários"]. */
  nomeDoItem?: readonly [string, string];
}

/** "Página 2 de 5" com Anterior e Próxima. Os botões só aparecem se houver mais de uma página. */
export function Paginacao({
  pagina,
  tamanho,
  total,
  aoMudar,
  nomeDoItem = ['resultado', 'resultados'],
}: PaginacaoProps) {
  const paginas = Math.max(1, Math.ceil(total / tamanho));
  const resumo = `${total} ${total === 1 ? nomeDoItem[0] : nomeDoItem[1]}`;
  return (
    <nav className="cs-paginacao" aria-label="Páginas">
      <span>{resumo}</span>
      {paginas > 1 ? (
        <>
          <Botao disabled={pagina <= 0} onClick={() => aoMudar(pagina - 1)}>
            Anterior
          </Botao>
          <span aria-live="polite">{`Página ${pagina + 1} de ${paginas}`}</span>
          <Botao disabled={pagina >= paginas - 1} onClick={() => aoMudar(pagina + 1)}>
            Próxima
          </Botao>
        </>
      ) : null}
    </nav>
  );
}
