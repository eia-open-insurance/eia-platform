# eia-platform

Platform runtime monorepo: gateway, auth, config, lineage, and shared Java libraries.

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-platform
- Type: Platform product area
- Deployable: No (contains multiple deployable components)

## Components

| Component | Path | Type | Deployable |
|---|---|---|---|
| Gateway | `gateway/` | Spring Cloud Gateway | Yes |
| Auth | `auth/` | Spring Authorization Server | Yes |
| Config | `config/` | Spring Cloud Config Server | Yes |
| Lineage | `lineage/` | Spring Boot service | Yes |
| Shared Java libraries | `libs/` | Java library | No |

## Verification

Run the project baseline check from the repository root:

```powershell
pwsh ../verify.ps1
```

No hosted CI is used. Component builds and tests run locally.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

