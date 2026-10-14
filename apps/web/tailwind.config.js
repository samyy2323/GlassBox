/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Channel-based so opacity modifiers work: bg-fg/10, text-muted/70, border-fg/20 ...
        fg: 'rgb(var(--fg-rgb) / <alpha-value>)',
        muted: 'rgb(var(--muted-rgb) / <alpha-value>)',
        canvas: 'rgb(var(--bg-rgb) / <alpha-value>)',
        accent: 'rgb(var(--accent-rgb) / <alpha-value>)',
        accent2: 'rgb(var(--accent2-rgb) / <alpha-value>)',
        ok: 'rgb(var(--ok-rgb) / <alpha-value>)',
        warn: 'rgb(var(--warn-rgb) / <alpha-value>)',
      },
      fontFamily: {
        sans: ['"Inter var"', 'Inter', 'ui-sans-serif', 'system-ui', '"Segoe UI Variable"', '"Segoe UI"', 'Roboto', '"Helvetica Neue"', 'Arial', 'sans-serif'],
        mono: ['ui-monospace', '"Cascadia Code"', '"SF Mono"', 'Menlo', 'Consolas', 'monospace'],
      },
      keyframes: {
        'orb-a': { '0%,100%': { transform: 'translate(0,0) scale(1)' }, '50%': { transform: 'translate(7vw,5vh) scale(1.12)' } },
        'orb-b': { '0%,100%': { transform: 'translate(0,0) scale(1)' }, '50%': { transform: 'translate(-8vw,7vh) scale(1.18)' } },
        'orb-c': { '0%,100%': { transform: 'translate(0,0) scale(1.08)' }, '50%': { transform: 'translate(5vw,-7vh) scale(0.95)' } },
        'fade-up': { from: { opacity: '0', transform: 'translateY(8px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'slide-in-right': { from: { opacity: '0', transform: 'translateX(28px)' }, to: { opacity: '1', transform: 'translateX(0)' } },
        'pop-in': { from: { opacity: '0', transform: 'scale(0.97)' }, to: { opacity: '1', transform: 'scale(1)' } },
        shimmer: { '0%': { backgroundPosition: '-200% 0' }, '100%': { backgroundPosition: '200% 0' } },
        'glow-pulse': { '0%,100%': { boxShadow: '0 0 0 0 var(--glow)' }, '50%': { boxShadow: '0 0 0 9px transparent' } },
        caret: { '0%,100%': { opacity: '1' }, '50%': { opacity: '0' } },
      },
      animation: {
        'orb-a': 'orb-a 40s ease-in-out infinite',
        'orb-b': 'orb-b 48s ease-in-out infinite',
        'orb-c': 'orb-c 56s ease-in-out infinite',
        'fade-up': 'fade-up 0.4s cubic-bezier(0.22,1,0.36,1) both',
        'fade-in': 'fade-in 0.25s ease both',
        'slide-in-right': 'slide-in-right 0.32s cubic-bezier(0.22,1,0.36,1) both',
        'pop-in': 'pop-in 0.18s cubic-bezier(0.22,1,0.36,1) both',
        shimmer: 'shimmer 2.4s linear infinite',
        'glow-pulse': 'glow-pulse 2.6s ease-out infinite',
        caret: 'caret 1s steps(1) infinite',
      },
    },
  },
  plugins: [],
}
