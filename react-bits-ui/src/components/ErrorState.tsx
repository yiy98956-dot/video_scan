interface Props {
  message: string
  onRetry?: () => void
}

export default function ErrorState({ message, onRetry }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-20 animate-fade-in">
      <div className="w-16 h-16 rounded-full bg-surface border border-border flex items-center justify-center mb-5">
        <svg className="w-7 h-7 text-gold" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
            d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
        </svg>
      </div>
      <p className="text-text-secondary mb-6 text-center max-w-md">{message}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="px-6 py-2.5 rounded-lg border border-gold/20 text-gold text-sm
            hover:bg-gold/5 transition-all duration-300"
        >
          重试
        </button>
      )}
    </div>
  )
}
