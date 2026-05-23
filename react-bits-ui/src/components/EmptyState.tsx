interface Props {
  message?: string
  hint?: string
}

export default function EmptyState({ message = '暂无内容', hint }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-20 animate-fade-in">
      <div className="w-16 h-16 rounded-full bg-surface border border-border flex items-center justify-center mb-5">
        <svg className="w-7 h-7 text-text-secondary" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
            d="M20.25 7.5l-.625 10.632a2.25 2.25 0 01-2.247 2.118H6.622a2.25 2.25 0 01-2.247-2.118L3.75 7.5m8.25 3v6.75m0 0l-3-3m3 3l3-3M3.375 7.5h17.25c.621 0 1.125-.504 1.125-1.125v-1.5c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v1.5c0 .621.504 1.125 1.125 1.125z" />
        </svg>
      </div>
      <p className="text-text-secondary mb-2">{message}</p>
      {hint && <p className="text-text-secondary/60 text-sm">{hint}</p>}
    </div>
  )
}
