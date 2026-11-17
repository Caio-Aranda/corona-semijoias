import { useState, type ReactNode } from 'react';

export interface PainelDePecaLidaProps {
  nome: string;
  codigo: string;
  /** Preço já pronto, normalmente um <ValorTresMoedas />. */
  preco: ReactNode;
  /** Endereço da foto. Sem foto (ou foto que não abre), aparece um espaço reservado e a venda segue. */
  fotoUrl?: string | null;
  /** Resultado da leitura: cor e texto juntos. */
  resultado?: { tipo: 'sucesso' | 'erro'; texto: string };
  /**
   * Linhas a mais sob o código (por exemplo, "Na maleta: 3", "COD Fábrica: X", "Categoria: Anéis"). Quem não tem o dado
   * não manda a linha, e ela não aparece. Os rótulos vêm de quem usa o painel (cada aplicativo tem os seus textos).
   */
  detalhes?: readonly { rotulo: string; valor: ReactNode }[];
  /** Textos fixos do painel (padrão em português; o Corona Vendas passa os do idioma escolhido). */
  textos?: {
    pecaLida: (nome: string) => string;
    foto: (nome: string) => string;
    semFoto: string;
    codigo: (codigo: string) => string;
  };
}

const TEXTOS_PADRAO = {
  pecaLida: (nome: string) => `Peça lida: ${nome}`,
  foto: (nome: string) => `Foto de ${nome}`,
  semFoto: 'Sem foto',
  codigo: (codigo: string) => `Código ${codigo}`,
};

/** Peça lida pelo leitor de código: foto grande, nome, código e preço. */
export function PainelDePecaLida({
  nome,
  codigo,
  preco,
  fotoUrl,
  resultado,
  detalhes,
  textos = TEXTOS_PADRAO,
}: PainelDePecaLidaProps) {
  const [fotoFalhou, setFotoFalhou] = useState(false);
  const mostrarFoto = Boolean(fotoUrl) && !fotoFalhou;
  return (
    <section
      className={`cs-peca${resultado ? ` cs-peca--${resultado.tipo}` : ''}`}
      aria-label={textos.pecaLida(nome)}
    >
      {mostrarFoto ? (
        <img
          className="cs-peca__foto"
          src={fotoUrl ?? undefined}
          alt={textos.foto(nome)}
          onError={() => {
            setFotoFalhou(true);
          }}
        />
      ) : (
        <div className="cs-peca__sem-foto" data-testid="sem-foto">
          {textos.semFoto}
        </div>
      )}
      <div>
        <h2 className="cs-peca__nome">{nome}</h2>
        <div className="cs-peca__codigo">{textos.codigo(codigo)}</div>
        <div>{preco}</div>
        {(detalhes ?? []).map((d) => (
          <div key={d.rotulo} className="cs-peca__detalhe">
            {d.rotulo}: {d.valor}
          </div>
        ))}
        {resultado ? (
          <p className="cs-peca__situacao" role={resultado.tipo === 'erro' ? 'alert' : 'status'}>
            {resultado.texto}
          </p>
        ) : null}
      </div>
    </section>
  );
}
