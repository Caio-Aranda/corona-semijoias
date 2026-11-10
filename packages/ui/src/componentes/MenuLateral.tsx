export interface ItemDoMenu {
  chave: string;
  rotulo: string;
}

export interface GrupoDoMenu {
  /** Título do grupo, ex.: "Cadastros". Sem título, os itens ficam soltos no topo. */
  titulo?: string;
  itens: readonly ItemDoMenu[];
}

export interface MenuLateralProps {
  grupos: readonly GrupoDoMenu[];
  /** Chave do item aberto agora. */
  atual: string;
  aoEscolher: (chave: string) => void;
  /** Descrição para leitores de tela. */
  rotulo?: string;
}

/** Menu lateral: grupos de itens, o aberto marcado, tudo navegável pelo teclado. */
export function MenuLateral({ grupos, atual, aoEscolher, rotulo = 'Menu' }: MenuLateralProps) {
  return (
    <nav className="cs-menu" aria-label={rotulo}>
      {grupos.map((grupo, i) => (
        <div key={grupo.titulo ?? `grupo-${i}`} className="cs-menu__grupo">
          {grupo.titulo ? <p className="cs-menu__titulo">{grupo.titulo}</p> : null}
          <ul>
            {grupo.itens.map((item) => (
              <li key={item.chave}>
                <button
                  type="button"
                  className="cs-menu__item"
                  aria-current={item.chave === atual ? 'page' : undefined}
                  onClick={() => aoEscolher(item.chave)}
                >
                  {item.rotulo}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}
