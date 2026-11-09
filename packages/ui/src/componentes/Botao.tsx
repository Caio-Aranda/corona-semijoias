import type { ButtonHTMLAttributes, ReactNode } from 'react';

export interface BotaoProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variante?: 'primario' | 'secundario' | 'perigoso';
  /** Tamanho grande, para telas de trabalho (venda, leitura do código). */
  grande?: boolean;
}

/** Botão. Os nomes e a posição seguem o glossário (parte 4): quem usa escolhe o texto, ex.: "Salvar". */
export function Botao({
  variante = 'secundario',
  grande = false,
  type = 'button',
  className,
  ...resto
}: BotaoProps) {
  const classes = ['cs-botao', `cs-botao--${variante}`, grande ? 'cs-botao--grande' : '', className]
    .filter(Boolean)
    .join(' ');
  return <button {...resto} type={type} className={classes} />;
}

export interface RodapeDeAcoesProps {
  /** Botão "Cancelar" (desistir sem salvar). Fica à esquerda; nunca no canto direito. */
  cancelar?: ReactNode;
  /** Botão principal ("Salvar", "Continuar", "Concluir" ou a ação nomeada). Fica à direita. */
  principal: ReactNode;
}

/** Rodapé de tela ou janela: Cancelar à esquerda, botão principal à direita (glossário, parte 4). */
export function RodapeDeAcoes({ cancelar, principal }: RodapeDeAcoesProps) {
  return (
    <div className={`cs-rodape-acoes${cancelar ? '' : ' cs-rodape-acoes--so-principal'}`}>
      {cancelar}
      {principal}
    </div>
  );
}
