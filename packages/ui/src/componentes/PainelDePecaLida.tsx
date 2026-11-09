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
}

/** Peça lida pelo leitor de código: foto grande, nome, código e preço. */
export function PainelDePecaLida({
  nome,
  codigo,
  preco,
  fotoUrl,
  resultado,
}: PainelDePecaLidaProps) {
  const [fotoFalhou, setFotoFalhou] = useState(false);
  const mostrarFoto = Boolean(fotoUrl) && !fotoFalhou;
  return (
    <section
      className={`cs-peca${resultado ? ` cs-peca--${resultado.tipo}` : ''}`}
      aria-label={`Peça lida: ${nome}`}
    >
      {mostrarFoto ? (
        <img
          className="cs-peca__foto"
          src={fotoUrl ?? undefined}
          alt={`Foto de ${nome}`}
          onError={() => {
            setFotoFalhou(true);
          }}
        />
      ) : (
        <div className="cs-peca__sem-foto" data-testid="sem-foto">
          Sem foto
        </div>
      )}
      <div>
        <h2 className="cs-peca__nome">{nome}</h2>
        <div className="cs-peca__codigo">Código {codigo}</div>
        <div>{preco}</div>
        {resultado ? (
          <p className="cs-peca__situacao" role={resultado.tipo === 'erro' ? 'alert' : 'status'}>
            {resultado.texto}
          </p>
        ) : null}
      </div>
    </section>
  );
}
