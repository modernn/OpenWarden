/* OpenWarden site — progressive enhancement only.
   The page is fully functional with this file blocked. */
/* Mark JS as available immediately so reveal styles can engage without a
   flash. Runs synchronously from <head>; the rest waits for the DOM. */
document.documentElement.classList.add("js");

(function () {
  "use strict";

  function init() {
  /* --- Sticky header shadow once scrolled --------------------------------- */
  var header = document.querySelector(".site-header");
  if (header) {
    var onScroll = function () {
      header.dataset.stuck = window.scrollY > 8 ? "true" : "false";
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  /* --- Mobile nav toggle -------------------------------------------------- */
  var toggle = document.querySelector(".nav__toggle");
  var links = document.getElementById("nav-links");
  if (toggle && links) {
    var setOpen = function (open) {
      toggle.setAttribute("aria-expanded", String(open));
      links.dataset.open = String(open);
      toggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    };
    setOpen(false);
    toggle.addEventListener("click", function () {
      setOpen(toggle.getAttribute("aria-expanded") !== "true");
    });
    links.addEventListener("click", function (e) {
      if (e.target.closest("a")) setOpen(false);
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && toggle.getAttribute("aria-expanded") === "true") {
        setOpen(false);
        toggle.focus();
      }
    });
  }

  /* --- Scroll reveal ------------------------------------------------------ */
  var reveals = document.querySelectorAll(".reveal");
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  if (reduce || !("IntersectionObserver" in window) || !reveals.length) {
    reveals.forEach(function (el) { el.classList.add("is-in"); });
    return;
  }

  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) {
        entry.target.classList.add("is-in");
        io.unobserve(entry.target);
      }
    });
  }, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });

  reveals.forEach(function (el) { io.observe(el); });

  /* Failsafe: never leave content hidden if IO is slow or doesn't fire
     (some embedded/headless engines). Content visibility must not depend
     on the animation succeeding. */
  window.setTimeout(function () {
    reveals.forEach(function (el) { el.classList.add("is-in"); });
  }, 1400);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
