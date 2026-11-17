const CAMINHOS: Record<string, string[]> = {
  painel: ['M4 20V10', 'M10 20V4', 'M16 20v-8', 'M22 20H2'],
  montar: ['M3 8h18v12H3z', 'M8 8V5h8v3', 'M3 13h18'],
  receber: ['M12 3v12', 'M7 10l5 5 5-5', 'M4 19h16'],
  acerto: ['M5 12l5 5 9-10', 'M3 4h18v16H3z'],
  cadastrar: ['M12 5v14', 'M5 12h14', 'M3 3h18v18H3z'],
  relatorios: ['M6 3h9l4 4v14H6z', 'M9 12h7', 'M9 16h7'],
};

/** Ícone de linha, dourado escuro, só para enfeitar o atalho (o nome do atalho já diz tudo). */
export function IconeDoAtalho({ nome }: { nome: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="28"
      height="28"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {(CAMINHOS[nome] ?? CAMINHOS.relatorios ?? []).map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  );
}
