interface Props {
  variant?: 'card' | 'list' | 'detail'
  count?: number
}

function CardSkeleton() {
  return (
    <div className="rounded-lg overflow-hidden bg-surface border border-border">
      <div className="aspect-[3/4] bg-surface-2 animate-shimmer-gold" />
      <div className="p-2.5 space-y-2">
        <div className="h-3.5 bg-surface-2 rounded animate-shimmer-gold w-3/4" />
        <div className="h-3 bg-surface-2 rounded animate-shimmer-gold w-1/2" />
      </div>
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="flex gap-4 p-4 rounded-lg bg-surface border border-border">
      <div className="w-20 h-28 bg-surface-2 rounded animate-shimmer-gold flex-shrink-0" />
      <div className="flex-1 space-y-2.5 py-1">
        <div className="h-4 bg-surface-2 rounded animate-shimmer-gold w-1/3" />
        <div className="h-3 bg-surface-2 rounded animate-shimmer-gold w-1/2" />
        <div className="h-3 bg-surface-2 rounded animate-shimmer-gold w-2/3" />
      </div>
    </div>
  )
}

function DetailSkeleton() {
  return (
    <div className="min-h-screen pb-24">
      <div className="relative h-[40vh] bg-surface-2 animate-shimmer-gold" />
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 -mt-32 relative z-10">
        <div className="flex flex-col md:flex-row gap-8">
          <div className="w-48 h-72 bg-surface rounded-lg animate-shimmer-gold flex-shrink-0 mx-auto md:mx-0" />
          <div className="flex-1 space-y-4 pt-4">
            <div className="h-8 bg-surface-2 rounded animate-shimmer-gold w-64" />
            <div className="h-4 bg-surface-2 rounded animate-shimmer-gold w-96" />
            <div className="h-4 bg-surface-2 rounded animate-shimmer-gold w-48" />
            <div className="h-20 bg-surface-2 rounded animate-shimmer-gold w-full" />
          </div>
        </div>
      </div>
    </div>
  )
}

export default function LoadingSkeleton({ variant = 'card', count = 12 }: Props) {
  if (variant === 'detail') return <DetailSkeleton />
  if (variant === 'list') {
    return (
      <div className="space-y-3">
        {Array.from({ length: count }).map((_, i) => (
          <ListSkeleton key={i} />
        ))}
      </div>
    )
  }
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
      {Array.from({ length: count }).map((_, i) => (
        <CardSkeleton key={i} />
      ))}
    </div>
  )
}
