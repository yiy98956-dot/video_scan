interface Props {
  current: number
  total: number
  onChange: (page: number) => void
}

export default function Pagination({ current, total, onChange }: Props) {
  if (total <= 1) return null

  const getPages = (): number[] => {
    if (total <= 7) {
      return Array.from({ length: total }, (_, i) => i + 1)
    }
    const pages: number[] = []
    if (current <= 4) {
      for (let i = 1; i <= 7; i++) pages.push(i)
    } else if (current >= total - 3) {
      for (let i = total - 6; i <= total; i++) pages.push(i)
    } else {
      for (let i = current - 3; i <= current + 3; i++) pages.push(i)
    }
    return pages
  }

  const pages = getPages()

  return (
    <div className="flex items-center justify-center gap-1.5 mt-10 relative z-20">
      <button
        type="button"
        onClick={() => { console.log('Prev clicked', current); onChange(current - 1) }}
        disabled={current <= 1}
        className="px-3.5 py-2 rounded-lg text-xs bg-surface border border-border text-text-secondary
          hover:border-gold/20 hover:text-text transition-all duration-200
          disabled:opacity-30 disabled:cursor-not-allowed"
      >
        上一页
      </button>

      {pages.map((page) => (
        <button
          type="button"
          key={page}
          onClick={() => { console.log('Page clicked', page); onChange(page) }}
          className={`w-9 h-9 rounded-lg text-xs font-medium transition-all duration-200 ${
            page === current
              ? 'bg-gold/10 text-gold border border-gold/20'
              : 'bg-surface border border-border text-text-secondary hover:border-gold/15 hover:text-text'
          }`}
        >
          {page}
        </button>
      ))}

      <button
        type="button"
        onClick={() => { console.log('Next clicked', current, total); if (current < total) onChange(current + 1) }}
        disabled={current >= total}
        className="px-3.5 py-2 rounded-lg text-xs bg-surface border border-border text-text-secondary
          hover:border-gold/20 hover:text-text transition-all duration-200
          disabled:opacity-30 disabled:cursor-not-allowed"
      >
        下一页
      </button>
    </div>
  )
}
