package com.lockbar.app.xposed;

/**
 * 电池图标的原始 PNG（96×138，取自参照包 {@code ic_battery_normal.png} /
 * {@code ic_battery_charging.png}），以 base64 内嵌 —— xposed 侧拿不到模块资源，
 * 内嵌是最稳的做法；运行时解一次，按 16×23dp 缩放绘制。
 */
final class LockBatteryIconData {

    private LockBatteryIconData() {
    }

    static final String NORMAL =
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAACKCAYAAABVR0/cAAAABHNCSVQICAgIfAhkiAAAAAFzUkdCAK7OHOkAAAd9SURBVHic" +
            "7ZtNbFzVFcd/x/OCIQHyhUjDdxIKCokSiUChQuKjYgO0SF3QCrXqln2FuqjYIrFjh8SCBVKzqFhRxIeC1CaoLZC2qagQEEKC" +
            "SYE4BrsxcT489uSwuOflvlgznjf22Md2z08azX+u773vzjnvnnfPvWOhB1T1RuAq+/gEsBZoAT+1suPAA6bfBR7spf8lzH7g" +
            "x6YPADeafgMYAMaBV6zstIj8t27H0q5QVcU6BrgV2FbR15teD1xhfWywshawxvSVwN11B7LEOQicMX2GbJsxez8LnDJ9AvjM" +
            "9FHgiOmWiOjMjosOFxSgYXo78LDpHcD9poeB62p/heXNj7r8/Stgs+l3gMOm9wFDppV0g17CwMyCYHG5JASpajkjdgNPml4N" +
            "7DHdIM+a3Qs+uuXFB/beAqZN/xOYNL23rCMi5d9zCJoR9zeQDXyW7lMwaH9DDpOehQBvYvZVVSmfBxGCnKk+hDcCvza9zl6Q" +
            "HsLB3LiTNAsA7gPuMP0HYBQudcAg8EPTDeCuRRjgSucGewH8C7jG9GBZIUKQM4Wq7jB9M/CU6fdqtH2f9LA+DTxrZWPAIdNN" +
            "ETnfr4F6oqqXA5fZxz2kJBTgGdJDdhS4t0s3Oyt1XlfV9RAzwJ1wgDOiqs+bvgD8tkv9t4Ctpn8GjAAqIuMLNL4ljaquJSWz" +
            "m4BXrfgY8EiXps9bu5gB3hTAlOnGbBWNk6SHLqQMeZq0yTQnVHUz6U4YAK614hadNwnnyzT5e46QZr2KyIl59CekHdJyK6LO" +
            "wuOCvShIoQRywjAbH5KmGMCoiJyrPdT2PEZaXQwAv7KyIXI+0m+OALeY3ksywiTw0lw6E5EzAKp6AfijFW/t3OIie4AfQIQg" +
            "dwrSKRbARI36E1gKjU2heTJIXl9/ae9THer2g2blOtWd3fnSIttl82wVjTEbCwX52PBojYYngX+bbtYfX0d+TjpB+x/5GbCQ" +
            "7Kjoy0lHqhPAC/Pst0lOQDfWqL8dO2WMEORMAfzNdIPuO59T2AFDu/PNOXCAFIaEfNBdALf1oe92fEo+LPnc3uc9k0VEVbU8" +
            "eJmetXJiBFv0FMBDPVxLRWSye7XanCCvgn5iZUN97H8mA2TnHiSvguaNiDTh4oqoG+UvRyIEedPrKqAfYafKIVLoGyAnMOdJ" +
            "59ALwVnSwxdSTtOizS8VFpNeHdD2d0RzRUQOVT6+38++lwsRgpzp1QH9SL6CCr06IGZMnwmDOhMhyJkIQc6EQZ0JBzgTDnAm" +
            "HOBMOMCZcIAzkQc4E3mAM2FQZyIEORMhyJkwqDO9OqDfZ8L/9/TqgL6eCQcRgtyJEORMhCBnIgQ5E4mYM5GIORMGdSZCkDMR" +
            "gpwJgzoTDnAmHOBMOMCZcIAz4QBnIg9wJvIAZ8KgzkQIciZCkDNhUGfiTNiZOBN2JkKQMxGCnIkQ5EyEIGciEXMmEjFnwqDO" +
            "RAhyJkKQM2FQZ8IBzoQDnAkHOBMOcCYc4EzkAc5EHuBMGNSZCEHORAhyJgzqTJwJOxNnws5ECHImQpAzEYKciRDkTCRizkQi" +
            "5kwY1JkIQc5ECHImDOpMOMCZcIAz4QBnwgHOhAOciTzAmcgDnAmDOhMhyJkIQc6EQZ0pgL+YbgD3d6kvqjoIICKTdS+iqruA" +
            "W+zjlfZaikzYC2BIRP5Tt6GqXmayzk29HwvnBXCfFR6t0XAVMGgXbIpI3UP6XcCDpq8GttZst9gcA74zvR+o5QBVFaB0QKNG" +
            "k43AFogQ5E4BvG26CWzvUn8tOZR8DEwtzLDcuAAMmz7XQ7uCPKvX16j/CfB52XCbFQ53rJ65BrjV9BFWngMGgM2mP+mh3Sqy" +
            "XTbUqL8R2FReMHCkAF4z3SA/KDuxE7jb9EFVPQWoiJzp0u5t4B+mB0l3zFJkCihXd6e6VVbVNaQfq60DfmnF52tc5xD2K8OC" +
            "bIw6We4m7OkNrCYt2bquhETkJHCyRv/LjYLkgDXAbis71kO7CEHeFMCrpq8lTQ2AceChNvW3AGW4eZrk+XOq+rKVfQd8anpa" +
            "RFbEQ1pVV5FsBXA7cJXp3wBXkGxS2mUb7dlPyoEA3gVGsE6/tcLqNHqvQye3V/QU6Yl/GnjDyhqVga6kjbsB8vdaT15q3knK" +
            "6kdNz8Yqsn0nMLtHCHLm4s/NVfV64Pf2sQE85TKilcuLQMv0syLyNcQMcCcc4Ew1BAk5J3gA+J3ps8DjizyulcKfyFvvzwEH" +
            "TE+VO8kxA5wpl1aIiKpquXQcAz4wvRo4aLq6zCyXVEGitFcLmDb9JXlrYwxbmlfPUdr+052qVte9jwIPm95BPjUbBq7rw8BX" +
            "Al+Rd1HfAQ6b3ge8abopIq2ZDSMEOVN0KFdyJvstae8fUggqZ02TNMWUS3c3S6euIx/eLHeGyLuj1Qx/imSPU8BnVnaYvB3z" +
            "DXnt33bTsqf/+1XVm8j7IL8gnZC1gMes7Dh5S/vvpNXUSuDPwD2m/wrcYHof6YYbB16xstMi8kXdjiMEOdPrDFhLPv3fSTpc" +
            "EfIOYBM7aiPtjNY5H10OjJJn/gjZBuXe/3ngI9OTIjK+iGML5sP3fi2Q7yLRDT8AAAAASUVORK5CYII=";

    static final String CHARGING =
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAACKCAYAAABVR0/cAAAABHNCSVQICAgIfAhkiAAAAAFzUkdCAK7OHOkAAAqqSURBVHic" +
            "7Z1dbFxHFYC/2d3EaVJIXLdN0z+aNJC0iRpo+CktaloUEFBAQuJHCMQLD5XgDSFAwGslJB4qXkA8gASiSMALBdGiVNAkSmkb" +
            "IEDLT0La1GmSJnFqN07sxF57fXiYc3M2Zn/uXV/fuevMJ63u2fHc2fHMnXPn58wZRwZE5BbgTfr1U8BqoAF8VMNeBXao/Czw" +
            "QJb0S8xu4L0q7wFuUfkJoAKMA7/SsPPOuWNpE3atAkXEacIAG4Hbm+SbVB4ErtI0rtGwBrBK5auBd6XNSMnZD0yqPImVzZhe" +
            "LwBnVT4JvKTyy8BhlRvOOZmfcK3NDzqgqvIdwE6VtwD3q3wKuDH1v9DfvLvL308A61TeCxxSeRcwrLLgH9DLqMwPiBTLZSpI" +
            "RJIWsQ34rMorge0qV7FWs23Rc9df/EOvDWBW5b8A0yo/lsRxziV/NxU0T+9fgxXwBbo3wUjrB/IU/l0I8CRaviLikvdBVEGB" +
            "aX4JDwGfV3mNfsC/hCO9cTe+FQDcB9yp8s+AUbi8AgaAt6pcBd5ZQAaXOjfrB+CvwLUqDyQRogoKTE1Etqj8FuBhlZ9Lce/z" +
            "+Jf1eeARDRsDDqhcd85N5ZXRkIjICmC5ft2OH4QCfBv/kh0F7umSzNamOL8TkUGILSA4sQIC40TkUZXngK90if97YIPKHwNG" +
            "AHHOjS9S/kqNiKzGD2bXAo9r8BHgw11ufVTviy0gNDVgRuVqp4jKafxLF/wIeRY/ydQTIrIO/yRUgOs1uEH7ScKFMov9nyP4" +
            "Vi/OuZMLSM/hZ0iTqYg0HY85/VDDqxKwAUMn/olvYgCjzrmLqbPamofwvYsK8DkNG8bGI3lzGLhN5cfwhTAN/KiXxJxzkwAi" +
            "Mgf8QoM3tL/jEtuBGyCqoODU8KtYABMp4k+gQ2i0CS2QAax/fVyvM23i5kG96XeaZ3YXSgMrl3WdIipjmhdq2LLhyyluPA38" +
            "TeV6+vy15RP4FbQ3sHfAYrKlSV6BX1KdAL6/wHTr2AB0KEX8O9BVxqiCAlMDnlG5SveZzxl0gaHV+mYP7MGrIYctdNeAt+WQ" +
            "div+iy2WvKLXBbdk55yISLLwMtsxsmcE7fTUgAcz/JY456a7R0vNSawX9H4NG84x/flUsMrdj/WCFoxzrg6XekTdSCxHogoK" +
            "TdZeQB5qp5kDeNVXwQYwU/h16MXgAv7lC35M06CFpUKRZK2AlnZEveKcO9D09fk80+4XogoKTNYKyGPwFWkiawXEFpMzsUAD" +
            "E1VQYKIKCkws0MDECghMrIDAxAoITKyAwMQKCEzWybjSjQNEJFlEmsLWt+dyWjBadLJWQBlbzLf0egwzEr60cld2yligVxR9" +
            "qYJE5DpgmX5NFnIa2IJRX6gf6F8V9AC2OfyLen0R+K7KpXhQ0lCWAr1iCb0mnP6H/Y6Sq/TrRuC6FtEa866lJ+iacEYGMauz" +
            "nXS2HQqZz0xEFRSYUqsgEalg9vybMK8tpzELtMQzSRrz+tJRdhVUxaynt2EWZevxFQL2ULxYYL5yI6qgwJR9ILYZc/q0Huv7" +
            "t6JvBl/NlH0gtga/gRy8p67EfUKrvQx90/NpJqqgwJROBemAK5li3g7cq/J57EW7sdWti5y1RaGMKmg55lVkLd7lC8C/6eyl" +
            "K6qgSHYWa0N0JkSkivnV2Qp8XeURrOfzvhRJTUJu26cKIbaAwMQKCExQFaRuMh2+v/+NJBjr+WShAnxA052it15R4vsB4JRz" +
            "7kinyHkQW0BgQo8DhvAPwQpsEWV1xjQSLyWn8NMV4K0iemkB05g1RSHu1kKPA3bg+/0rgS/3mMbbm+QPLTA/BzCfGb/G3A8s" +
            "GlEFBaZwFSQiA9giyyC+BSxvf0chJE/6UeA1lXt14pSJECpoLbYR+0v4AyFezyHdXhnH3juzwPdUPts6er5EFRSYQtaE1TN7" +
            "0r9+B74VgPdYMkCYB+FpvEqtYz2p45jjpVL2gnqdcWw+keNezIn1/cCbe0xzodyM99s2DvxUw84458ba35I/UQUFpiizlM3A" +
            "rSqvxJwkhXr6AfbhW+U0fq0B0vnNy5WiVNCd2PzOPZTDNf4k/v+/CBzUsMKNeqMKCkxRA7F/4T0jgvcLlJw11qtK24ktW34w" +
            "w32PYw/dz/E9nQaL6yqzI0UNxCawCpjATpBo9JjmJ7F3SlZu0OuofoLuJ4sqKDBFqaARrAVUsJe50NuL/Qg2UNrcJe7T2P+5" +
            "R/MCfvr6Qg+/nSuFqCB1dZnbrkURGcIW8bsxg9kZTWJW1PXmA9VCEVVQYEphltIDFTo/PM8A71H5KP5oXfDH0h6G8piuxBYQ" +
            "mFgBgelXFdSNKXzvB+AP6GnXzrnS7R+OLSAwoc1SeqVVPibx7unBdzWTZc5RSmy6HtospVda5eMEdgrHfuAHKp8pS4+nFWUp" +
            "0CuWflVBI5i3lGRtoYGZlxzDphmCzXSmoV9V0PXY5r2EOeCXKr/inDtWbJZ6oywFesVSalcFHTiJ5SXZsjoCvKDyG/93R0kp" +
            "u6uCdmzEvKX8Wa8TzrkX2sQvLVEFBaZfVdDfMePZvXotxJYzb/pVBQlmabdPr3kcrVg4UQUFpl8HYj/GLCuSJcay5C0TfTkQ" +
            "c84Nh85DXpSiQK9k4iE+gYmH+AQmFmhgYgUEJlZAYGIFBCZWQGBiBQQmjgMCE8cBgYkFGpioggITVVBgYoEGpoaZcVfxzjM6" +
            "4dThUrLvKxUichdwm369Wj9lZAJzVzCcxcpCRBKnU2ke6t2oOq8B92lgK5fw81mGrkSJSD2D0etd+LO/wPuH2JDyvqI5ApxT" +
            "eTdmZ9QRdceTVEC1U1xlCHUwGFVQYGrAUyrXse2c7ViNqZL/UHLD1x6Yw9aYL2a4r4a16sEU8Q/inVVRw04mSnMK0bWY7/7D" +
            "LL0KqADrVD7YKeI8lmHl0umYlYQh1GtYVEGBqQG/VbmKvSjbsRU7VGe/iJwFxDk32eW+pzAbzgHMtr9sNJ9D3NXSTkRWYb6v" +
            "P6PBaXzNHUCtDGtYYaQZ5a7F3AOvxHfZuvaEnHOn8YevLTUS5+OrsNM90jj8Tu6LKig0NbwTI/C7TpItPuPAgy3ir0dPqQC+" +
            "iq/5iyLyEw07h+1UnHXOLYmXtIgsw4zYNuGdzQJ8AX/C6yRWLrfTmt2Yj7xnUa8tNWw7Z3Mzeq5NIpua5Bn8G/888ISGVZsy" +
            "upQm7irY/zWIdTXvxo/qR7HDhtqxDCvfCbTcowoKzCVzcxG5Cfimfq0CDwfJ0dLlh9gZCY84516D2AKCEysgMM0qyGFjgh3A" +
            "11S+AHy84HwtFX6DTb1/B++zDmAmmUmOLSAwlzZoOOdERJKu4xjm0n0l3vkFXN7N7HSu45VIUl4N7Lj149jUxhjaNW9eR2m5" +
            "6U7Pck8K+iN4T7UAW7BVs1PAjTlkfClwAptF3QscUnkX8KTKdedcY/6NUQUFpt0eMcFGsq+jngbxKihpNXV8ExMun91MKnUN" +
            "tnjT7wxjs6PNI/wZfHmcBV7SsEPYdMwZrO/fctIy075fEbkVmwf5NH6FrAE8pGGvYlPaf8L3ppYCf8TcYO7DnMbuwj9w46hf" +
            "OuC8c+5o2oSjCgpM1hawGlv934pfXHHYDGAdO6DnHOnWR/uBUazlj2BlkMz9T2GncEw758YLzFtkIfwPlFhX+9OgTNsAAAAA" +
            "SUVORK5CYII=";

}
