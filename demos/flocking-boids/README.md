# Flocking Boids

This demo implements a classic boids flocking simulation in pure NetLogo. Each boid follows local separation, alignment, and cohesion rules, and flock-level motion emerges from those local interactions alone.

With the default parameters, the population settles into coordinated swarms, curved bands, and occasional V-like formations as clusters split and rejoin over time.

## How To Run

1. Open `flocking-boids.nlogox` in NetLogo 7.0.3+.
2. Click `setup`.
3. Click `go`.

## What Emerges

The flock self-organizes into coordinated swarms, arcing lanes, and occasional V-like formations. `avg-speed` reports the mean boid speed, and `flock-size` reports the largest local flock detected within the cohesion radius.

## Reference

Reynolds, C. W. (1986). "Flocks, herds and schools: A distributed behavioral model." SIGGRAPH '86.
