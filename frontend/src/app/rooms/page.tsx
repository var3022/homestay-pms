import Image from "next/image";
import Link from "next/link";
const rooms = [
    {
        name: "Deluxe Room",
        category: "PRIVATE ACCOMMODATION",
        description:
            "A comfortable private room designed for a relaxed and restful stay.",
        occupancy: "Standard occupancy and extra-bed options to be confirmed",
        image:
            "https://images.unsplash.com/photo-1611892440504-42a792e24d32?auto=format&fit=crop&w=1000&q=85",
    },
    {
        name: "Family Room",
        category: "FOR FAMILIES",
        description:
            "A welcoming option for families or small groups travelling together.",
        occupancy: "Capacity and facilities to be confirmed",
        image:
            "https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=1000&q=85",
    },
    {
        name: "Shared Accommodation",
        category: "DORMITORY",
        description:
            "A practical shared-stay option for guests who prefer communal accommodation.",
        occupancy: "Available capacity to be confirmed",
        image:
            "https://images.unsplash.com/photo-1555854877-bab0e564b8d5?auto=format&fit=crop&w=1000&q=85",
    },
];

export default function RoomsPage() {
    return (
        <main className="min-h-screen bg-[#f8f6f0] text-[#263c32]">
            <header className="border-b border-[#e5e0d5]">
                <div className="mx-auto flex max-w-7xl flex-col gap-4 px-4 py-5 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                    <Link href="/" className="font-serif text-2xl tracking-wide">
                        YOUR HOMESTAY
                    </Link>
                    <nav className="flex items-center gap-6 text-sm">
                        <Link href="/" className="hover:opacity-70">
                            Home
                        </Link>
                        <Link href="/rooms" className="font-medium underline underline-offset-4">
                            Rooms
                        </Link>
                        <Link
                            href="/#stay"
                            className="rounded-full bg-[#294638] px-4 py-3 !text-white hover:bg-[#1e352a] sm:px-5"
                        >
                            Enquire now
                        </Link>
                    </nav>
                </div>
            </header>

            <section className="mx-auto max-w-7xl px-6 pb-12 pt-16 md:pb-16 md:pt-24">
                <p className="mb-4 text-xs font-semibold tracking-[0.25em] text-[#9b8050]">
                    FIND YOUR SPACE
                </p>
                <h1 className="max-w-3xl font-serif text-4xl leading-tight md:text-6xl">
                    Rooms made for slowing down.
                </h1>
                <p className="mt-6 max-w-2xl text-base leading-8 text-[#68746b] md:text-lg">
                    Whether you are travelling solo, with family or with friends,
                    discover the accommodation options available for your stay.
                </p>
            </section>

            <section className="mx-auto grid max-w-7xl gap-8 px-6 pb-20 md:grid-cols-2 lg:grid-cols-3">
                {rooms.map((room) => (
                    <article
                        key={room.name}
                        className="overflow-hidden rounded-2xl border border-[#e8e2d7] bg-white"
                    >
                        <div className="relative aspect-[4/3] overflow-hidden bg-[#e9e5dc]">
                            <Image
                                src={room.image}
                                alt={room.name}
                                fill
                                sizes="(max-width: 639px) 100vw, (max-width: 1023px) 50vw, 33vw"
                                className="object-cover transition duration-500 hover:scale-105"
                            />
                        </div>

                        <div className="p-6">
                            <p className="text-[10px] font-semibold tracking-[0.2em] text-[#9b8050]">
                                {room.category}
                            </p>
                            <h2 className="mt-3 font-serif text-2xl">{room.name}</h2>
                            <p className="mt-3 min-h-16 text-sm leading-7 text-[#68746b]">
                                {room.description}
                            </p>
                            <p className="mt-4 border-t border-[#eee9df] pt-4 text-xs leading-6 text-[#68746b]">
                                {room.occupancy}
                            </p>
                            <Link
                                href="/#stay"
                                className="mt-5 inline-flex items-center gap-2 text-sm font-semibold text-[#294638] hover:gap-3"
                            >
                                Enquire about a stay <span aria-hidden="true">→</span>
                            </Link>
                        </div>
                    </article>
                ))}
            </section>

            <section className="bg-[#294638] px-6 py-16 text-center text-white md:py-20">
                <p className="text-xs tracking-[0.25em] text-[#d6c29a]">
                    YOUR NEXT STAY STARTS HERE
                </p>
                <h2 className="mx-auto mt-4 max-w-2xl font-serif text-3xl md:text-4xl">
                    A little more comfort. A little more you.
                </h2>
                <p className="mx-auto mt-4 max-w-xl text-sm leading-7 text-white/75">
                    Share your dates and group size with us to enquire about your stay.
                </p>
                <Link
                    href="/#stay"
                    className="mt-8 inline-block rounded-full bg-[#f8f6f0] px-7 py-4 text-sm font-medium !text-[#294638] hover:bg-white"
                >
                    Plan your stay
                </Link>
            </section>

            <footer className="px-6 py-7 text-center text-xs text-[#68746b]">
                © 2026 · MADE FOR FEELING AT HOME
            </footer>
        </main>
    );
}
