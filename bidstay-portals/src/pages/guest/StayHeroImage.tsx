export default function StayHeroImage() {
  return <picture className="guest-hero-image" aria-hidden="true">
    <source media="(max-width: 690px)" srcSet="/images/stay-hero-mobile.webp" />
    <img src="/images/stay-hero.webp" alt="" width={1440} height={960} fetchPriority="high" />
  </picture>;
}
