import Link from "next/link";

const rooms = [
  {
    number: "01",
    name: "The Deluxe Room",
    detail: "A quiet retreat with thoughtful comforts.",
    features: "Queen beds · Air conditioning · Ensuite bathroom",
    image:
      "https://images.unsplash.com/photo-1611892440504-42a792e24d32?auto=format&fit=crop&w=1100&q=85",
  },
  {
    number: "02",
    name: "The Balcony Room",
    detail: "Slow mornings and a little more breathing room.",
    features: "Private balcony · Air conditioning · Ensuite bathroom",
    image:
      "https://images.unsplash.com/photo-1590490360182-c33d57733427?auto=format&fit=crop&w=1100&q=85",
  },
  {
    number: "03",
    name: "The Family Room",
    detail: "Space to settle in, reconnect and feel at home.",
    features: "Flexible sleeping · Shared moments · Modern comforts",
    image:
      "https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=1100&q=85",
  },
];

const amenities = [
  ["01", "A comfortable stay", "Air-conditioned rooms and considered essentials"],
  ["02", "Room to arrive", "Parking options and lift access"],
  ["03", "Little pleasures", "Books, board games and time to unwind"],
  ["04", "Everyday ease", "Laundry facilities available on request"],
];

export default function Home() {
  return (
    <main>
      <div className="announcement">
        A place to pause, settle in and feel at home.
      </div>

      <header className="site-header">
        <Link href="/" className="brand" aria-label="Homestay home">
          <span className="brand-mark">H.</span>
          <span className="brand-copy">
            <strong>YOUR HOMESTAY</strong>
            <small>A MORE PERSONAL STAY</small>
          </span>
        </Link>

        <nav className="desktop-nav" aria-label="Main navigation">
          <a href="#stay">The stay</a>
          <a href="#experience">Experience</a>
          <a href="#location">Location</a>
        </nav>

        <Link className="header-cta" href="#booking">
          Plan your stay <span aria-hidden="true">↗</span>
        </Link>
      </header>

      <section className="hero">
        <div className="hero-image" role="img" aria-label="An inviting, sunlit homestay bedroom" />
        <div className="hero-shade" />

        <div className="hero-content">
          <p className="eyebrow light-eyebrow">
            <span /> A STAY THAT FEELS DIFFERENT
          </p>
          <h1>
            Come as you are.
            <br />
            <em>Feel at home.</em>
          </h1>
          <p className="hero-description">
            Thoughtful spaces, unhurried mornings and the comfort of feeling
            like you belong somewhere.
          </p>
          <a className="hero-button" href="#stay">
            Find your kind of stay <span aria-hidden="true">↗</span>
          </a>
        </div>

        <div className="hero-caption">
          <span>YOUR OWN LITTLE CORNER OF COMFORT</span>
          <span>01 — A PLACE TO BELONG</span>
        </div>
      </section>

      <section className="booking-wrap" id="booking">
        <form className="booking-panel" action="#stay">
          <div className="booking-intro">
            <span className="booking-icon">✳</span>
            <div>
              <strong>Make yourself at home</strong>
              <p>Start planning your next stay.</p>
            </div>
          </div>

          <label className="booking-field">
            <span>CHECK-IN</span>
            <input type="date" name="checkIn" aria-label="Check-in date" />
          </label>

          <label className="booking-field">
            <span>CHECK-OUT</span>
            <input type="date" name="checkOut" aria-label="Check-out date" />
          </label>

          <label className="booking-field guest-field">
            <span>GUESTS</span>
            <select name="guests" defaultValue="2">
              <option value="1">1 guest</option>
              <option value="2">2 guests</option>
              <option value="3">3 guests</option>
              <option value="4">4 guests</option>
              <option value="5">5+ guests</option>
            </select>
          </label>

          <button className="booking-button" type="submit">
            Explore rooms <span aria-hidden="true">→</span>
          </button>
        </form>
        <p className="booking-note">
          Dates are a starting point. Live availability will be connected to
          the PMS in a later step.
        </p>
      </section>

      <section className="intro-section" id="experience">
        <div className="intro-label">
          <span className="section-index">01 / THE PHILOSOPHY</span>
          <span className="small-line" />
        </div>
        <div className="intro-copy">
          <p className="eyebrow">MORE THAN A ROOM</p>
          <h2>
            Some places give you
            <br />
            a bed. <em>Some give you a feeling.</em>
          </h2>
          <p className="intro-description">
            We believe the best stays are the ones that let you be yourself.
            No fuss, no formality—just welcoming spaces, considered comforts
            and room for your own kind of journey.
          </p>
          <a className="text-link" href="#stay">
            Get to know the stay <span aria-hidden="true">↗</span>
          </a>
        </div>
        <div className="intro-stamp" aria-hidden="true">
          <span>STAY AWHILE</span>
          <strong>H.</strong>
          <span>FEEL AT HOME</span>
        </div>
      </section>

      <section className="rooms-section" id="stay">
        <div className="section-heading">
          <div>
            <p className="eyebrow">YOUR SPACE, YOUR PACE</p>
            <h2>Find your <em>place.</em></h2>
          </div>
          <p className="section-aside">
            Different ways to stay.
            <br />
            The same feeling of home.
          </p>
        </div>

        <div className="room-grid">
          {rooms.map((room) => (
            <article className="room-card" key={room.number}>
              <div className="room-image-wrap">
                <div
                  className="room-image"
                  role="img"
                  aria-label={room.name}
                  style={{ backgroundImage: `url("${room.image}")` }}
                />
                <span className="room-number">{room.number}</span>
                <span className="room-image-label">YOUR SPACE TO UNWIND</span>
              </div>
              <div className="room-card-content">
                <h3>{room.name}</h3>
                <p>{room.detail}</p>
                <span className="room-features">{room.features}</span>
                <a href="#booking" className="room-link">
                  Enquire about this room <span aria-hidden="true">↗</span>
                </a>
              </div>
            </article>
          ))}
        </div>
        <p className="room-disclaimer">
          Room categories and imagery are illustrative for this first design
          preview. Live room details and rates will come from your PMS.
        </p>
      </section>

      <section className="amenities-section">
        <div className="amenities-heading">
          <p className="eyebrow">THE LITTLE THINGS MATTER</p>
          <h2>Comfort, without <em>compromise.</em></h2>
          <p>
            Everything you need for an easy stay, with a few thoughtful extras
            along the way.
          </p>
        </div>

        <div className="amenities-list">
          {amenities.map((amenity) => (
            <div className="amenity-row" key={amenity[0]}>
              <span className="amenity-number">{amenity[0]}</span>
              <h3>{amenity[1]}</h3>
              <p>{amenity[2]}</p>
              <span className="amenity-arrow" aria-hidden="true">↗</span>
            </div>
          ))}
        </div>
      </section>

      <section className="location-section" id="location">
        <div className="location-image" role="img" aria-label="Warm, welcoming interiors" />
        <div className="location-copy">
          <p className="eyebrow light-eyebrow">YOUR NEXT CHAPTER STARTS HERE</p>
          <h2>A little closer to <em>where you want to be.</em></h2>
          <p>
            Whether you are here for work, a weekend away or time with your
            favourite people, let your stay be the easy part of the journey.
          </p>
          <a className="location-button" href="#booking">
            Ask about your stay <span aria-hidden="true">↗</span>
          </a>
          <span className="location-footnote">
            Property address and directions will be added here.
          </span>
        </div>
      </section>

      <footer className="site-footer">
        <Link href="/" className="footer-brand">H. <span>YOUR HOMESTAY</span></Link>
        <p>A more personal kind of stay.</p>
        <div className="footer-links">
          <a href="#stay">Rooms</a>
          <a href="#experience">Experience</a>
          <a href="#booking">Enquiries</a>
        </div>
        <span className="copyright">© 2026 · MADE FOR FEELING AT HOME</span>
      </footer>
    </main>
  );
}